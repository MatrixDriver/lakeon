"""Digest (reflection) prompt builder — ported from neuromem-cloud."""


def build_digest_prompt(memories: list[dict], existing_traits: list[dict] | None = None) -> str:
    """Build reflection prompt for digest."""
    existing_text = ""
    if existing_traits:
        recent = existing_traits[-20:]
        existing_lines = [f"- {t.get('content', '')}" for t in recent]
        existing_text = (
            f"\nExisting traits ({len(existing_traits)} total, showing last {len(recent)}):\n"
            + "\n".join(existing_lines) + "\n\n"
            + "STRICT deduplication rules:\n"
            + "- If a new trait expresses same/similar meaning as existing, SKIP it\n"
            + "- Only output NEW angles not yet captured\n"
            + '- If no new traits, return empty: {"traits": []}\n'
        )

    return f"""You are a memory analysis system. Based on the user's recent memories,
generate **incremental** behavioral pattern and summary traits.

{existing_text}Generation rules:
1. Each trait must synthesize MULTIPLE memories into deeper understanding
2. Categories:
   - pattern: Specific behavioral patterns with details
   - summary: Summary of recent experiences with temporal context
3. importance (1-10): 9-10 = core personality/values; 7-8 = useful patterns; below 7 = do NOT output
4. If no worthwhile new traits, return empty list

Return ONLY valid JSON:
{{"traits": [{{"content": "...", "category": "pattern|summary", "importance": 7}}]}}"""


def format_memories_for_digest(memories: list[dict]) -> str:
    """Format memories as numbered list for digest prompt context."""
    lines = []
    for i, m in enumerate(memories):
        content = m.get("content", "")
        mtype = m.get("memory_type", "unknown")
        lines.append(f"{i+1}. [{mtype}] {content}")
    return "\n".join(lines)
