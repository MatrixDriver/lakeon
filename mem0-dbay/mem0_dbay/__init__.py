"""mem0-dbay: PostgreSQL + pgvector graph store backend for Mem0.

One database replaces three. No more Neo4j.

Usage:
    import mem0_dbay  # registers 'dbay' graph provider in Mem0

    # Use mem0_dbay.create_memory() instead of Memory.from_config()
    m = mem0_dbay.create_memory({
        "graph_store": {
            "provider": "dbay",
            "config": {"connection_string": "postgresql://user:pass@host:5432/db"}
        },
        "vector_store": {
            "provider": "pgvector",
            "config": {"dbname": "mydb", "host": "localhost", "port": 5432,
                       "user": "user", "embedding_model_dims": 1024}
        },
        "llm": {"provider": "openai", "config": {"api_key": "sk-xxx"}},
        "embedder": {"provider": "openai", "config": {"api_key": "sk-xxx"}},
    })
"""

from mem0_dbay.config import DbayGraphConfig


def _register():
    """Register dbay graph store into Mem0's factory."""
    try:
        from mem0.utils.factory import GraphStoreFactory
        GraphStoreFactory.provider_to_class["dbay"] = (
            "mem0_dbay.memory_graph.MemoryGraph"
        )
    except ImportError:
        pass


def create_memory(config_dict: dict):
    """Create a Mem0 Memory instance with dbay graph store.

    Bypasses Mem0's pydantic config validation (which doesn't know about 'dbay' provider)
    by using model_construct to build the config object without validation.
    """
    from mem0.configs.base import MemoryConfig
    from mem0.graphs.configs import GraphStoreConfig
    from mem0.memory.main import Memory

    # Extract graph_store config and build it separately
    gs_dict = config_dict.get("graph_store", {})
    gs_config = gs_dict.get("config", {})

    # Build GraphStoreConfig using model_construct (skip validation)
    graph_store = GraphStoreConfig.model_construct(
        provider=gs_dict.get("provider", "dbay"),
        config=DbayGraphConfig(**gs_config) if gs_dict.get("provider") == "dbay" else gs_config,
        llm=gs_dict.get("llm"),
        custom_prompt=gs_dict.get("custom_prompt"),
        threshold=gs_dict.get("threshold", 0.7),
    )

    # Build the rest of config normally (without graph_store)
    config_without_graph = {k: v for k, v in config_dict.items() if k != "graph_store"}
    # Set graph_store to None temporarily to pass validation
    config_without_graph["graph_store"] = {"provider": "neo4j"}

    mem_config = MemoryConfig(**config_without_graph)

    # Now swap in the real graph_store config
    object.__setattr__(mem_config, 'graph_store', graph_store)

    return Memory(mem_config)


_register()
