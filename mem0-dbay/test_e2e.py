#!/usr/bin/env python3
"""End-to-end test for mem0-dbay: Mem0 with PostgreSQL graph store.

Usage:
    # With SiliconFlow:
    SILICONFLOW_API_KEY=sk-xxx python test_e2e.py
"""
import os
import sys

# Register dbay provider BEFORE importing Memory
import mem0_dbay  # noqa: F401

from mem0 import Memory

DB_NAME = os.environ.get("DB_NAME", "mem0_dbay_test")
DB_HOST = os.environ.get("DB_HOST", "localhost")
DB_PORT = os.environ.get("DB_PORT", "5432")
DB_USER = os.environ.get("DB_USER", os.environ.get("USER", "postgres"))
DB_PASSWORD = os.environ.get("DB_PASSWORD", "")

CONNECTION_STRING = f"postgresql://{DB_USER}:{DB_PASSWORD}@{DB_HOST}:{DB_PORT}/{DB_NAME}"
if not DB_PASSWORD:
    CONNECTION_STRING = f"postgresql://{DB_USER}@{DB_HOST}:{DB_PORT}/{DB_NAME}"

SILICONFLOW_API_KEY = os.environ.get("SILICONFLOW_API_KEY")
OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY")

SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1"
SILICONFLOW_LLM_MODEL = "deepseek-ai/DeepSeek-V3"
SILICONFLOW_EMBED_MODEL = "BAAI/bge-m3"
SILICONFLOW_EMBED_DIM = 1024


def build_config():
    api_key = SILICONFLOW_API_KEY or OPENAI_API_KEY
    if not api_key:
        print("ERROR: Set SILICONFLOW_API_KEY or OPENAI_API_KEY")
        sys.exit(1)

    config = {
        "version": "v1.1",
        "graph_store": {
            "provider": "dbay",
            "config": {
                "connection_string": CONNECTION_STRING,
            }
        },
        "vector_store": {
            "provider": "pgvector",
            "config": {
                "dbname": DB_NAME,
                "host": DB_HOST,
                "port": int(DB_PORT),
                "user": DB_USER,
                "password": DB_PASSWORD or None,
                "embedding_model_dims": SILICONFLOW_EMBED_DIM if SILICONFLOW_API_KEY else 1536,
            }
        },
    }

    if SILICONFLOW_API_KEY:
        config["llm"] = {
            "provider": "openai",
            "config": {
                "api_key": SILICONFLOW_API_KEY,
                "openai_base_url": SILICONFLOW_BASE_URL,
                "model": SILICONFLOW_LLM_MODEL,
            }
        }
        config["embedder"] = {
            "provider": "openai",
            "config": {
                "api_key": SILICONFLOW_API_KEY,
                "openai_base_url": SILICONFLOW_BASE_URL,
                "model": SILICONFLOW_EMBED_MODEL,
            }
        }
        config["graph_store"]["config"]["embedding_dimension"] = SILICONFLOW_EMBED_DIM
    else:
        config["llm"] = {
            "provider": "openai",
            "config": {"api_key": OPENAI_API_KEY}
        }
        config["embedder"] = {
            "provider": "openai",
            "config": {"api_key": OPENAI_API_KEY}
        }

    return config


def main():
    config = build_config()

    print("=" * 60)
    print("mem0-dbay E2E Test")
    print("=" * 60)
    print(f"Database: {DB_HOST}:{DB_PORT}/{DB_NAME}")
    print(f"LLM: {'SiliconFlow' if SILICONFLOW_API_KEY else 'OpenAI'}")
    print()

    user_id = "test_user_e2e"

    # 1. Initialize
    print("[1/6] Initializing Mem0 with dbay graph store...")
    m = mem0_dbay.create_memory(config)
    print("      OK")

    # 2. Clean
    print("[2/6] Cleaning previous test data...")
    m.delete_all(user_id=user_id)
    print("      OK")

    # 3. Add memories
    print("[3/6] Adding memories...")
    r1 = m.add("Alice works at Google as a software engineer", user_id=user_id)
    print(f"      Result 1: {r1}")

    r2 = m.add("Alice loves Python and hates Java", user_id=user_id)
    print(f"      Result 2: {r2}")

    r3 = m.add("Bob is Alice's manager at Google", user_id=user_id)
    print(f"      Result 3: {r3}")

    # 4. Search
    print("[4/6] Searching: 'Where does Alice work?'...")
    results = m.search("Where does Alice work?", user_id=user_id)
    print(f"      Search results ({len(results)}):")
    for r in results:
        print(f"        - {r}")
    if not results:
        print("      WARNING: No search results (may be normal if graph is empty)")

    # 5. Get all memories
    print("[5/6] Getting all memories...")
    all_memories = m.get_all(user_id=user_id)
    print(f"      Total memories: {len(all_memories)}")
    for mem in all_memories[:5]:
        print(f"        - {mem.get('memory', mem)}")

    # 6. Check graph
    print("[6/6] Checking graph relationships...")
    if m.graph:
        all_rels = m.graph.get_all(filters={"user_id": user_id})
        print(f"      Graph relationships ({len(all_rels)}):")
        for rel in all_rels:
            src = rel.get("source", "?")
            tgt = rel.get("target", rel.get("destination", "?"))
            r = rel.get("relationship", "?")
            print(f"        {src} --[{r}]--> {tgt}")
    else:
        print("      Graph not available")

    print()
    print("=" * 60)
    print("E2E TEST COMPLETE")
    print("=" * 60)


if __name__ == "__main__":
    main()
