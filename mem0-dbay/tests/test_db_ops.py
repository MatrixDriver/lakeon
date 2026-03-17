"""Test MemoryGraphDB low-level operations (no LLM, mock embeddings)."""
import pytest
from mem0_dbay.memory_graph import MemoryGraphDB


@pytest.fixture
def db(clean_db):
    return MemoryGraphDB(clean_db)


def _emb(x, y, z, w):
    """Create a simple 4D embedding."""
    return [float(x), float(y), float(z), float(w)]


class TestNodeOperations:
    def test_insert_node(self, db):
        nid = db.insert_node("alice", _emb(1, 0, 0, 0), {"user_id": "u1"})
        assert nid > 0

    def test_insert_node_with_type(self, db):
        nid = db.insert_node("google", _emb(0, 1, 0, 0), {"user_id": "u1"}, node_type="organization")
        rows = db.pg.query("SELECT node_type FROM graph_nodes WHERE id = %s", (nid,))
        assert rows[0]["node_type"] == "organization"

    def test_find_similar_node_found(self, db):
        emb = _emb(1, 0, 0, 0)
        db.insert_node("bob", emb, {"user_id": "u1"})
        found = db.find_similar_node(emb, {"user_id": "u1"}, threshold=0.9)
        assert found is not None
        assert found["name"] == "bob"

    def test_find_similar_node_not_found(self, db):
        db.insert_node("charlie", _emb(1, 0, 0, 0), {"user_id": "u1"})
        found = db.find_similar_node(_emb(0, 0, 0, 1), {"user_id": "u1"}, threshold=0.9)
        assert found is None

    def test_find_similar_node_user_isolation(self, db):
        emb = _emb(1, 0, 0, 0)
        db.insert_node("secret", emb, {"user_id": "u2"})
        found = db.find_similar_node(emb, {"user_id": "u1"}, threshold=0.5)
        assert found is None

    def test_increment_mentions(self, db):
        nid = db.insert_node("dave", _emb(1, 0, 0, 0), {"user_id": "u1"})
        db.increment_node_mentions(nid)
        rows = db.pg.query("SELECT mentions FROM graph_nodes WHERE id = %s", (nid,))
        assert rows[0]["mentions"] == 2


class TestEdgeOperations:
    def test_insert_edge(self, db):
        n1 = db.insert_node("alice", _emb(1, 0, 0, 0), {"user_id": "u1"})
        n2 = db.insert_node("google", _emb(0, 1, 0, 0), {"user_id": "u1"})
        eid = db.insert_edge(n1, n2, "works_at")
        assert eid > 0

    def test_insert_edge_duplicate_increments_mentions(self, db):
        n1 = db.insert_node("alice", _emb(1, 0, 0, 0), {"user_id": "u1"})
        n2 = db.insert_node("google", _emb(0, 1, 0, 0), {"user_id": "u1"})
        db.insert_edge(n1, n2, "works_at")
        db.insert_edge(n1, n2, "works_at")  # duplicate
        rows = db.pg.query(
            "SELECT mentions FROM graph_edges WHERE source_id=%s AND target_id=%s",
            (n1, n2)
        )
        assert rows[0]["mentions"] == 2

    def test_delete_edge(self, db):
        n1 = db.insert_node("alice", _emb(1, 0, 0, 0), {"user_id": "u1"})
        n2 = db.insert_node("google", _emb(0, 1, 0, 0), {"user_id": "u1"})
        db.insert_edge(n1, n2, "works_at")
        count = db.delete_edge("alice", "google", "works_at", {"user_id": "u1"})
        assert count == 1

    def test_delete_edge_not_found(self, db):
        count = db.delete_edge("nobody", "nowhere", "x", {"user_id": "u1"})
        assert count == 0


class TestSearchOperations:
    def test_search_returns_relationships(self, db):
        n1 = db.insert_node("alice", _emb(1, 0, 0, 0), {"user_id": "u1"})
        n2 = db.insert_node("google", _emb(0, 1, 0, 0), {"user_id": "u1"})
        db.insert_edge(n1, n2, "works_at")

        results = db.search_similar_nodes_with_relations(
            _emb(1, 0, 0, 0), {"user_id": "u1"}, threshold=0.5
        )
        assert len(results) >= 1
        assert results[0]["source"] == "alice"
        assert results[0]["relationship"] == "works_at"
        assert results[0]["destination"] == "google"

    def test_search_respects_user_isolation(self, db):
        n1 = db.insert_node("secret", _emb(1, 0, 0, 0), {"user_id": "u2"})
        n2 = db.insert_node("corp", _emb(0, 1, 0, 0), {"user_id": "u2"})
        db.insert_edge(n1, n2, "owns")

        results = db.search_similar_nodes_with_relations(
            _emb(1, 0, 0, 0), {"user_id": "u1"}, threshold=0.5
        )
        assert len(results) == 0

    def test_search_bidirectional(self, db):
        n1 = db.insert_node("alice", _emb(1, 0, 0, 0), {"user_id": "u1"})
        n2 = db.insert_node("bob", _emb(0.9, 0.1, 0, 0), {"user_id": "u1"})
        db.insert_edge(n2, n1, "manages")  # bob -> alice

        results = db.search_similar_nodes_with_relations(
            _emb(1, 0, 0, 0), {"user_id": "u1"}, threshold=0.5
        )
        assert any(r["relationship"] == "manages" for r in results)


class TestBulkOperations:
    def test_delete_all_for_user(self, db):
        db.insert_node("x", _emb(1, 0, 0, 0), {"user_id": "u1"})
        db.insert_node("y", _emb(0, 1, 0, 0), {"user_id": "u2"})
        db.delete_all({"user_id": "u1"})
        rows_u1 = db.pg.query("SELECT * FROM graph_nodes WHERE user_id = %s", ("u1",))
        rows_u2 = db.pg.query("SELECT * FROM graph_nodes WHERE user_id = %s", ("u2",))
        assert len(rows_u1) == 0
        assert len(rows_u2) == 1

    def test_get_all_relationships(self, db):
        n1 = db.insert_node("a", _emb(1, 0, 0, 0), {"user_id": "u1"})
        n2 = db.insert_node("b", _emb(0, 1, 0, 0), {"user_id": "u1"})
        n3 = db.insert_node("c", _emb(0, 0, 1, 0), {"user_id": "u1"})
        db.insert_edge(n1, n2, "knows")
        db.insert_edge(n2, n3, "likes")
        rels = db.get_all_relationships({"user_id": "u1"}, limit=100)
        assert len(rels) == 2

    def test_reset(self, db):
        db.insert_node("z", _emb(1, 0, 0, 0), {"user_id": "u1"})
        db.reset()
        rows = db.pg.query("SELECT count(*) AS c FROM graph_nodes")
        assert rows[0]["c"] == 0
