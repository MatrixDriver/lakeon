from __future__ import annotations

import time
from fastapi import APIRouter, Request

from echomem.api.schemas import (
    TimelineResponse, TimelineEventOut,
    TreeResponse, TreeNodeOut,
    GraphResponse, GraphNodeOut, GraphEdgeOut,
    SkillsResponse, SkillOut,
)

router = APIRouter(prefix="/derivatives")


@router.get("/timeline", response_model=TimelineResponse)
async def timeline(
    request: Request,
    agent: str | None = None,
    start_ms: int | None = None,
    end_ms: int | None = None,
) -> TimelineResponse:
    driver = request.app.state.driver
    now = int(time.time() * 1000)
    end_ms = end_ms or now + 1
    start_ms = start_ms or 0
    events = driver.query_timeline(start_ms=start_ms, end_ms=end_ms, agent_id=agent)
    return TimelineResponse(events=[
        TimelineEventOut(
            id=e.id, window_start=e.window_start, window_end=e.window_end,
            agent_id=e.agent_id, title=e.title, summary=e.summary,
            member_memory_ids=e.member_memory_ids, rationale=e.rationale,
        ) for e in events
    ])


@router.get("/tree", response_model=TreeResponse)
async def tree(request: Request, source_kind: str, source_ref: str) -> TreeResponse:
    driver = request.app.state.driver
    nodes = driver.query_tree(source_kind=source_kind, source_ref=source_ref)
    return TreeResponse(levels=[
        TreeNodeOut(id=n.id, level=n.level, parent_id=n.parent_id, text=n.text,
                    token_estimate=n.token_estimate, rationale=n.rationale)
        for n in nodes
    ])


@router.get("/graph", response_model=GraphResponse)
async def graph(request: Request, seed: str, hops: int = 2) -> GraphResponse:
    driver = request.app.state.driver
    sub = driver.query_subgraph(seed_id=seed, hops=hops)
    return GraphResponse(
        nodes=[GraphNodeOut(id=n.id, name=n.name, kind=n.kind) for n in sub.nodes],
        edges=[
            GraphEdgeOut(subject_id=s, object_id=o, predicate=attrs["predicate"],
                         confidence=attrs.get("confidence", 0.0))
            for s, o, attrs in sub.edges
        ],
    )


@router.get("/skills", response_model=SkillsResponse)
async def skills(request: Request, ctx: str, k: int = 5) -> SkillsResponse:
    driver = request.app.state.driver
    ollama = request.app.state.ollama
    cfg = request.app.state.config
    if not ctx.strip():
        return SkillsResponse(skills=[])
    emb = await ollama.embed(ctx, model=cfg.embedding_model)
    hits = driver.query_skills(query_emb=emb, k=k)
    return SkillsResponse(skills=[
        SkillOut(id=s.id, name=s.name, trigger_pattern=s.trigger_pattern,
                 steps=s.steps, source=s.source,
                 observed_count=s.observed_count, success_count=s.success_count)
        for s in hits
    ])
