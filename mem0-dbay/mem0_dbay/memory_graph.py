"""Mem0-compatible graph memory backed by PostgreSQL + pgvector.

Two classes:
- MemoryGraphDB: low-level PG operations (nodes, edges, vector search)
- MemoryGraph: Mem0-compatible interface with LLM orchestration
"""

import logging

from rank_bm25 import BM25Okapi

from mem0.graphs.tools import (
    EXTRACT_ENTITIES_TOOL,
    EXTRACT_ENTITIES_STRUCT_TOOL,
    RELATIONS_TOOL,
    RELATIONS_STRUCT_TOOL,
    DELETE_MEMORY_TOOL_GRAPH,
    DELETE_MEMORY_STRUCT_TOOL_GRAPH,
    NOOP_TOOL,
    NOOP_STRUCT_TOOL,
)
from mem0.graphs.utils import EXTRACT_RELATIONS_PROMPT, get_delete_messages
from mem0.utils.factory import EmbedderFactory, LlmFactory

from mem0_dbay.config import DbayGraphConfig
from mem0_dbay.pg_client import PgClient
from mem0_dbay.schema import ensure_schema
from mem0_dbay.utils import sanitize_relationship, build_filter_conditions

logger = logging.getLogger(__name__)


# ─── Low-level database operations ──────────────────────────────────────────


class MemoryGraphDB:
    """Low-level PostgreSQL graph operations for Mem0."""

    def __init__(self, pg: PgClient):
        self.pg = pg

    # ── Node operations ──────────────────────────────────────────

    def insert_node(self, name: str, embedding: list[float], filters: dict,
                    node_type: str = None) -> int:
        """Insert a node, return its id."""
        row = self.pg.query(
            """INSERT INTO graph_nodes (name, node_type, user_id, agent_id, run_id, embedding)
               VALUES (%s, %s, %s, %s, %s, %s::vector)
               RETURNING id""",
            (name, node_type, filters.get("user_id"), filters.get("agent_id"),
             filters.get("run_id"), str(embedding))
        )
        return row[0]["id"]

    def find_similar_node(self, embedding: list[float], filters: dict,
                          threshold: float = 0.9) -> dict | None:
        """Find the most similar node by embedding cosine similarity."""
        where, params = build_filter_conditions(filters, "n")
        emb_str = str(embedding)
        # param order: emb for SELECT, filter params for WHERE, emb for ORDER BY
        all_params = tuple([emb_str] + params + [emb_str])
        rows = self.pg.query(f"""
            SELECT n.id, n.name,
                   1 - (n.embedding <=> %s::vector) AS similarity
            FROM graph_nodes n
            WHERE {where}
              AND n.embedding IS NOT NULL
            ORDER BY n.embedding <=> %s::vector
            LIMIT 1
        """, all_params)
        if rows and rows[0]["similarity"] >= threshold:
            return dict(rows[0])
        return None

    def increment_node_mentions(self, node_id: int) -> None:
        self.pg.execute(
            "UPDATE graph_nodes SET mentions = mentions + 1 WHERE id = %s",
            (node_id,)
        )

    # ── Edge operations ──────────────────────────────────────────

    def insert_edge(self, source_id: int, target_id: int, relationship: str) -> int:
        """Insert or update an edge. Returns edge id."""
        # Try insert first
        rows = self.pg.query(
            """INSERT INTO graph_edges (source_id, target_id, relationship)
               VALUES (%s, %s, %s)
               ON CONFLICT DO NOTHING
               RETURNING id""",
            (source_id, target_id, relationship)
        )
        if rows:
            return rows[0]["id"]
        # Edge exists — increment mentions
        self.pg.execute(
            """UPDATE graph_edges SET mentions = mentions + 1, updated_at = now()
               WHERE source_id = %s AND target_id = %s AND relationship = %s""",
            (source_id, target_id, relationship)
        )
        existing = self.pg.query(
            "SELECT id FROM graph_edges WHERE source_id=%s AND target_id=%s AND relationship=%s",
            (source_id, target_id, relationship)
        )
        return existing[0]["id"] if existing else 0

    def delete_edge(self, source_name: str, target_name: str, relationship: str,
                    filters: dict) -> int:
        """Delete edges matching source->relationship->target with filters. Returns count deleted."""
        where_src, p_src = build_filter_conditions(filters, "src")
        where_dst, p_dst = build_filter_conditions(filters, "dst")
        rows = self.pg.query(f"""
            DELETE FROM graph_edges e
            USING graph_nodes src, graph_nodes dst
            WHERE e.source_id = src.id AND e.target_id = dst.id
              AND src.name = %s AND dst.name = %s AND e.relationship = %s
              AND {where_src} AND {where_dst}
            RETURNING e.id
        """, tuple([source_name, target_name, relationship] + p_src + p_dst))
        return len(rows)

    # ── Search ───────────────────────────────────────────────────

    def search_similar_nodes_with_relations(self, embedding: list[float],
                                             filters: dict,
                                             threshold: float = 0.7,
                                             limit: int = 100) -> list[dict]:
        """Vector similarity search + 1-hop relationship expansion."""
        where, params = build_filter_conditions(filters, "n")
        emb_str = str(embedding)
        rows = self.pg.query(f"""
            WITH similar_nodes AS (
                SELECT n.id, n.name,
                       1 - (n.embedding <=> %s::vector) AS similarity
                FROM graph_nodes n
                WHERE {where} AND n.embedding IS NOT NULL
                  AND 1 - (n.embedding <=> %s::vector) >= %s
                ORDER BY n.embedding <=> %s::vector
                LIMIT 20
            )
            SELECT DISTINCT
                src.name AS source, src.id AS source_id,
                e.relationship, e.id AS relation_id,
                dst.name AS destination, dst.id AS destination_id,
                sn.similarity
            FROM similar_nodes sn
            JOIN graph_edges e ON e.source_id = sn.id OR e.target_id = sn.id
            JOIN graph_nodes src ON src.id = e.source_id
            JOIN graph_nodes dst ON dst.id = e.target_id
            ORDER BY sn.similarity DESC
            LIMIT %s
        """, tuple([emb_str] + params + [emb_str, threshold, emb_str, limit]))
        return [dict(r) for r in rows]

    # ── Bulk operations ──────────────────────────────────────────

    def delete_all(self, filters: dict) -> None:
        where, params = build_filter_conditions(filters)
        # Edges cascade-deleted via FK
        self.pg.execute(f"DELETE FROM graph_nodes WHERE {where}", tuple(params))

    def get_all_relationships(self, filters: dict, limit: int = 100) -> list[dict]:
        where_src, p1 = build_filter_conditions(filters, "src")
        where_dst, p2 = build_filter_conditions(filters, "dst")
        rows = self.pg.query(f"""
            SELECT src.name AS source, e.relationship, dst.name AS target
            FROM graph_edges e
            JOIN graph_nodes src ON src.id = e.source_id
            JOIN graph_nodes dst ON dst.id = e.target_id
            WHERE {where_src} AND {where_dst}
            LIMIT %s
        """, tuple(p1 + p2 + [limit]))
        return [dict(r) for r in rows]

    def reset(self) -> None:
        self.pg.execute("DELETE FROM graph_edges")
        self.pg.execute("DELETE FROM graph_nodes")


# ─── Mem0-compatible MemoryGraph ─────────────────────────────────────────────


class MemoryGraph:
    """Mem0-compatible graph memory backed by PostgreSQL + pgvector.

    Drop-in replacement for Neo4j-based graph_memory.MemoryGraph.
    Register via: GraphStoreFactory.provider_to_class["dbay"] = "mem0_dbay.memory_graph.MemoryGraph"
    """

    def __init__(self, config):
        graph_config = config.graph_store.config
        if not isinstance(graph_config, dict):
            graph_config = graph_config.model_dump()

        pg_config = DbayGraphConfig(**graph_config)
        self.pg = PgClient(pg_config.connection_string)
        self.db = MemoryGraphDB(self.pg)
        ensure_schema(self.pg, pg_config.embedding_dimension)

        # Embedding model
        self.embedding_model = EmbedderFactory.create(
            config.embedder.provider,
            config.embedder.config,
        )

        # LLM — graph_store.llm takes priority, then falls back to config.llm
        if config.graph_store.llm:
            llm_provider = config.graph_store.llm.provider
            llm_config = config.graph_store.llm.config
        else:
            llm_provider = config.llm.provider
            llm_config = config.llm.config
        self.llm = LlmFactory.create(llm_provider, llm_config)

        self.threshold = config.graph_store.threshold or 0.7
        self.user_id = None

        # Detect if LLM supports structured output (OpenAI / Azure)
        self._use_structured = hasattr(self.llm, 'structured_output')

    # ── Public API ───────────────────────────────────────────────

    def add(self, data: str, filters: dict) -> dict:
        entity_type_map = self._retrieve_nodes_from_data(data, filters)
        to_be_added = self._establish_nodes_relations_from_data(data, filters, entity_type_map)

        node_list = list(entity_type_map.keys())
        search_output = self._search_graph_db(node_list, filters)
        to_be_deleted = self._get_delete_entities_from_search_output(search_output, data, filters)

        deleted = self._delete_entities(to_be_deleted, filters)
        added = self._add_entities(to_be_added, filters, entity_type_map)
        return {"deleted_entities": deleted, "added_entities": added}

    def search(self, query: str, filters: dict, limit: int = 100) -> list:
        entity_type_map = self._retrieve_nodes_from_data(query, filters)
        node_list = list(entity_type_map.keys())
        search_output = self._search_graph_db(node_list, filters, limit)

        if not search_output:
            return []

        # BM25 rerank
        search_output_str = [
            f"{r['source']} -- {r['relationship']} -- {r['destination']}"
            for r in search_output
        ]
        tokenized = [doc.split() for doc in search_output_str]
        bm25 = BM25Okapi(tokenized)
        scores = bm25.get_scores(query.split())

        scored = list(zip(search_output, scores))
        scored.sort(key=lambda x: x[1], reverse=True)

        return [
            {"source": r["source"], "relationship": r["relationship"],
             "destination": r["destination"]}
            for r, _ in scored[:5]
        ]

    def delete_all(self, filters: dict) -> None:
        self.db.delete_all(filters)

    def get_all(self, filters: dict, limit: int = 100) -> list:
        return self.db.get_all_relationships(filters, limit)

    def reset(self) -> None:
        self.db.reset()

    # ── LLM orchestration ────────────────────────────────────────

    def _retrieve_nodes_from_data(self, data: str, filters: dict) -> dict:
        """Use LLM to extract entities from text. Returns {entity_name: entity_type}."""
        user_id = filters.get("user_id", "unknown")

        if self._use_structured:
            tools = [EXTRACT_ENTITIES_STRUCT_TOOL]
        else:
            tools = [EXTRACT_ENTITIES_TOOL]

        messages = [
            {"role": "system", "content": f"You are a smart assistant who extracts entities from text. The user_id of the user is {user_id}."},
            {"role": "user", "content": data},
        ]

        response = self.llm.generate_response(messages=messages, tools=tools)
        entity_type_map = {}

        tool_calls = response.get("tool_calls", [])
        for call in tool_calls:
            if call.get("name") == "extract_entities":
                args = call.get("arguments", {})
                entities = args.get("entities", [])
                for ent in entities:
                    name = ent.get("entity", "").lower().replace(" ", "_")
                    etype = ent.get("entity_type", "")
                    if name:
                        entity_type_map[name] = etype

        return entity_type_map

    def _establish_nodes_relations_from_data(self, data: str, filters: dict,
                                              entity_type_map: dict) -> list:
        """Use LLM to extract relationships between entities."""
        user_id = filters.get("user_id", "unknown")

        if self._use_structured:
            tools = [RELATIONS_STRUCT_TOOL]
        else:
            tools = [RELATIONS_TOOL]

        entity_list = list(entity_type_map.keys())
        messages = [
            {"role": "system", "content": EXTRACT_RELATIONS_PROMPT.replace("USER_ID", user_id)},
            {"role": "user", "content": f"Entities: {entity_list}\n\nText: {data}"},
        ]

        response = self.llm.generate_response(messages=messages, tools=tools)
        relations = []

        tool_calls = response.get("tool_calls", [])
        for call in tool_calls:
            if call.get("name") == "establish_relationships":
                args = call.get("arguments", {})
                rels = args.get("relationships", [])
                for rel in rels:
                    relations.append({
                        "source": rel.get("source", "").lower().replace(" ", "_"),
                        "destination": rel.get("destination", "").lower().replace(" ", "_"),
                        "relationship": rel.get("relationship", ""),
                    })

        return relations

    def _search_graph_db(self, node_list: list, filters: dict, limit: int = 100) -> list:
        """Embed each node name and search for similar nodes + relationships."""
        all_results = []
        for node_name in node_list:
            embedding = self.embedding_model.embed(node_name)
            results = self.db.search_similar_nodes_with_relations(
                embedding, filters, self.threshold, limit
            )
            all_results.extend(results)

        # Deduplicate
        seen = set()
        unique = []
        for r in all_results:
            key = (r["source"], r["relationship"], r["destination"])
            if key not in seen:
                seen.add(key)
                unique.append(r)
        return unique

    def _get_delete_entities_from_search_output(self, search_output: list,
                                                 data: str, filters: dict) -> list:
        """Use LLM to decide which relationships to delete."""
        if not search_output:
            return []

        user_id = filters.get("user_id", "unknown")

        if self._use_structured:
            tools = [DELETE_MEMORY_STRUCT_TOOL_GRAPH, NOOP_STRUCT_TOOL]
        else:
            tools = [DELETE_MEMORY_TOOL_GRAPH, NOOP_TOOL]

        existing_str = "\n".join(
            f"- {r['source']} -- {r['relationship']} -- {r['destination']}"
            for r in search_output
        )
        system_msg, user_msg = get_delete_messages(existing_str, data, user_id)
        messages = [
            {"role": "system", "content": system_msg},
            {"role": "user", "content": user_msg},
        ]

        response = self.llm.generate_response(messages=messages, tools=tools)
        to_delete = []

        tool_calls = response.get("tool_calls", [])
        for call in tool_calls:
            if call.get("name") == "delete_graph_memory":
                args = call.get("arguments", {})
                to_delete.append({
                    "source": args.get("source", "").lower().replace(" ", "_"),
                    "destination": args.get("destination", "").lower().replace(" ", "_"),
                    "relationship": args.get("relationship", ""),
                })

        return to_delete

    def _add_entities(self, to_be_added: list, filters: dict,
                      entity_type_map: dict) -> list:
        """Add entity triples to the graph, deduplicating nodes by embedding."""
        results = []
        for triple in to_be_added:
            src_name = triple["source"]
            dst_name = triple["destination"]
            rel = sanitize_relationship(triple["relationship"])

            src_emb = self.embedding_model.embed(src_name)
            dst_emb = self.embedding_model.embed(dst_name)

            # Find or create source node
            existing_src = self.db.find_similar_node(src_emb, filters, threshold=0.9)
            if existing_src:
                src_id = existing_src["id"]
                self.db.increment_node_mentions(src_id)
            else:
                src_id = self.db.insert_node(
                    src_name, src_emb, filters,
                    node_type=entity_type_map.get(src_name)
                )

            # Find or create destination node
            existing_dst = self.db.find_similar_node(dst_emb, filters, threshold=0.9)
            if existing_dst:
                dst_id = existing_dst["id"]
                self.db.increment_node_mentions(dst_id)
            else:
                dst_id = self.db.insert_node(
                    dst_name, dst_emb, filters,
                    node_type=entity_type_map.get(dst_name)
                )

            self.db.insert_edge(src_id, dst_id, rel)
            results.append({"source": src_name, "relationship": rel, "target": dst_name})

        return results

    def _delete_entities(self, to_be_deleted: list, filters: dict) -> list:
        results = []
        for triple in to_be_deleted:
            src = triple["source"]
            dst = triple["destination"]
            rel = sanitize_relationship(triple["relationship"])
            count = self.db.delete_edge(src, dst, rel, filters)
            if count > 0:
                results.append(triple)
        return results
