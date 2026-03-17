import re


def sanitize_relationship(rel: str) -> str:
    """Convert relationship text to safe format: lowercase, underscored, ASCII-safe."""
    rel = rel.lower().strip()
    rel = re.sub(r'[^a-z0-9_]', '_', rel)
    rel = re.sub(r'_+', '_', rel)
    return rel.strip('_')


def build_filter_conditions(filters: dict, table_alias: str = "") -> tuple[str, list]:
    """Build WHERE clause fragments from filters dict.

    Returns (conditions_sql, params_list).
    conditions_sql is like "user_id = %s AND agent_id = %s" (no leading AND/WHERE).
    Returns ("TRUE", []) if no filters match.
    """
    prefix = f"{table_alias}." if table_alias else ""
    conditions = []
    params = []
    for key in ("user_id", "agent_id", "run_id"):
        val = filters.get(key)
        if val is not None:
            conditions.append(f"{prefix}{key} = %s")
            params.append(val)
    if not conditions:
        return "TRUE", []
    return " AND ".join(conditions), params
