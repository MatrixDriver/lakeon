"""Core agent loop — drives DeepSeek V3.2 through tool-calling rounds.

The runner is stateless; you construct it once with an LlmClient + LakeonApiClient
and then call `run_ingest` / `run_curate` / `run_lint` per request. Each call
handles its own run log write so the caller just needs to interpret the result.
"""
import dataclasses
import json
import logging
import time
from dataclasses import dataclass, field
from typing import Any

from ulid import ULID

from app.agent.tools import INGEST_FORBIDDEN, TOOL_SCHEMAS

log = logging.getLogger(__name__)


# ──────────────────────────────────────────────────────────────
# Data classes
# ──────────────────────────────────────────────────────────────


@dataclass
class RunRequest:
    tenant_id: str
    kb_id: str
    run_id: str
    source: str                    # queue | mcp | manual | curate-auto
    document_id: str | None = None
    run_type: str = "ingest"       # ingest | curate | lint
    # TODO(task-2.8): used by the /v1/wiki/* API routes to POST completion callback
    callback_url: str | None = None


@dataclass
class RunResult:
    status: str = "running"        # running | success | max_rounds_exceeded | error
    created_titles: set[str] = field(default_factory=set)
    updated_titles: set[str] = field(default_factory=set)
    deleted_titles: set[str] = field(default_factory=set)
    tool_calls_count: int = 0
    token_count: int = 0
    error: str | None = None
    summary: str | None = None

    @property
    def pages_created(self) -> int:
        return len(self.created_titles)

    @property
    def pages_updated(self) -> int:
        return len(self.updated_titles)

    @property
    def pages_deleted(self) -> int:
        return len(self.deleted_titles)


# ──────────────────────────────────────────────────────────────
# System prompts
# ──────────────────────────────────────────────────────────────


INGEST_SYSTEM_PROMPT = """你是一个 wiki 编译 agent，工作在一个 Karpathy 风格的知识库里。

你的目标：把一份新文档融入现有 wiki，**以更新为主、创建为辅**。

工作流程：
1. 先调 get_schema 读取本 KB 的规范（必须第一步）。
2. 调 list_pages 或 search_pages 了解现有内容。
3. 调 read_source 读取本次要处理的源文档。
4. 对每个发现的知识点：先 search_pages 找相关已有页，read_page 读全文；
   若合适则 update_page，否则才 create_page。
5. 结束前调 log_note 记录一行操作摘要，再调 done。

硬性规则：
- 严格遵守 schema 中的页数预算（通常每次 touch 5-15 页，create 不超过 2-3 页）。
- 不得调用 delete_page（ingest 流程禁止删除）。
- 创建新页前**必须**先 list_pages 或 search_pages 确认不重复。
- update_page 的 old_text 必须在页面中唯一匹配，否则先 read_page 扩大上下文再试。
- 所有页面正文使用简体中文。
"""


CURATE_SYSTEM_PROMPT = """你是一个 wiki 整理 agent。

目标：审视整个 wiki，合并重复、拆分过大、修复链接、删除离题或通用的内容。

工作流程：
1. get_schema 读规范。
2. list_pages 列出全部页面。
3. 对疑似重复或过于宽泛的页面分别 read_page 读全文。
4. 决定变更：
   - 合并：create_page 建合并页 → delete_page 删旧页
   - 改写：update_page
   - 删除通用/离题：delete_page
5. log_note 写一行总结，然后 done。

硬性规则：
- 只保留对本 KB 领域有价值的页面。
- 每次 curate 最多变更 ~15 页；避免大刀阔斧重写整个 wiki。
"""


LINT_SYSTEM_PROMPT = """你是一个 wiki lint agent。

目标：找出 wiki 中的问题并修复：空页、重复、死链 [[xxx]]、格式错误、与 schema 冲突。

工作流程：
1. get_schema 读规范。
2. list_pages 概览。
3. 逐页 read_page，识别问题。
4. 用 update_page / delete_page 修复。
5. log_note 总结，done。

硬性规则：
- 每次 lint 最多变更 ~10 页；避免大刀阔斧重写整个 wiki。
- 只修可证明的问题（死链、格式、空页），不做主观"这页写得不够好"的改写。
- 所有页面正文使用简体中文。
"""


# ──────────────────────────────────────────────────────────────
# Runner
# ──────────────────────────────────────────────────────────────


class AgentRunner:
    def __init__(
        self,
        llm,
        api,
        max_rounds: int = 20,
        max_tool_result_chars: int = 6000,
    ) -> None:
        self._llm = llm
        self._api = api
        self._max_rounds = max_rounds
        self._max_tool_result_chars = max_tool_result_chars

    async def run_ingest(self, req: RunRequest) -> dict[str, Any]:
        if req.run_type != "ingest":
            req = dataclasses.replace(req, run_type="ingest")
        return await self._run(req, INGEST_SYSTEM_PROMPT, forbid=INGEST_FORBIDDEN)

    async def run_curate(self, req: RunRequest) -> dict[str, Any]:
        if req.run_type != "curate":
            req = dataclasses.replace(req, run_type="curate")
        return await self._run(req, CURATE_SYSTEM_PROMPT, forbid=set())

    async def run_lint(self, req: RunRequest) -> dict[str, Any]:
        if req.run_type != "lint":
            req = dataclasses.replace(req, run_type="lint")
        return await self._run(req, LINT_SYSTEM_PROMPT, forbid=set())

    async def _run(
        self,
        req: RunRequest,
        system_prompt: str,
        forbid: set[str],
    ) -> dict[str, Any]:
        start = time.time()
        messages: list[dict[str, Any]] = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": self._user_message(req)},
        ]
        result = RunResult()

        try:
            should_stop = False
            for round_idx in range(self._max_rounds):
                llm_resp = await self._llm.chat(messages=messages, tools=TOOL_SCHEMAS)
                result.token_count += llm_resp["usage"]["total"]
                msg = llm_resp["message"]
                tool_calls = msg.get("tool_calls") or []

                if not tool_calls:
                    finish_reason = llm_resp.get("finish_reason")
                    if finish_reason == "length":
                        result.status = "error"
                        result.error = "llm hit max_tokens without calling done()"
                        break
                    # Plain content response — treat as implicit done
                    result.status = "success"
                    result.summary = msg.get("content") or ""
                    break

                messages.append(msg)

                # Separate done from other tools so we can execute real work first
                done_call = None
                regular_calls = []
                for tc in tool_calls:
                    if tc["function"]["name"] == "done":
                        done_call = tc
                    else:
                        regular_calls.append(tc)

                for tc in regular_calls:
                    name = tc["function"]["name"]
                    try:
                        args = json.loads(tc["function"]["arguments"] or "{}")
                    except json.JSONDecodeError as e:
                        messages.append(
                            self._tool_message(
                                tc["id"],
                                {"ok": False, "error": f"invalid JSON in arguments: {e}"},
                            )
                        )
                        result.tool_calls_count += 1
                        continue

                    result.tool_calls_count += 1

                    if name in forbid:
                        log.warning(
                            "Tool %s forbidden in %s run %s",
                            name,
                            req.run_type,
                            req.run_id,
                        )
                        messages.append(
                            self._tool_message(
                                tc["id"],
                                {"ok": False, "error": f"tool {name} is not allowed in {req.run_type}"},
                            )
                        )
                        continue

                    tool_result = await self._execute_tool(req, name, args)
                    self._track_counts(result, name, args, tool_result)
                    messages.append(self._tool_message(tc["id"], tool_result))

                if done_call is not None:
                    try:
                        done_args = json.loads(done_call["function"]["arguments"] or "{}")
                    except json.JSONDecodeError:
                        done_args = {}
                    result.tool_calls_count += 1
                    result.status = "success"
                    result.summary = done_args.get("summary", "")
                    messages.append(
                        self._tool_message(done_call["id"], {"ok": True, "acknowledged": True})
                    )
                    should_stop = True

                if should_stop:
                    break
            else:
                result.status = "max_rounds_exceeded"
                result.error = (
                    f"agent did not call done() within {self._max_rounds} rounds"
                )
        except Exception as e:
            log.exception("agent run %s failed: %s", req.run_id, e)
            result.status = "error"
            result.error = f"{type(e).__name__}: {e}"

        duration_ms = int((time.time() - start) * 1000)
        await self._write_runlog(req, result, duration_ms)

        return {
            "status": result.status,
            "pages_created": result.pages_created,
            "pages_updated": result.pages_updated,
            "pages_deleted": result.pages_deleted,
            "tool_calls_count": result.tool_calls_count,
            "token_count": result.token_count,
            "summary": result.summary,
            "error": result.error,
            "duration_ms": duration_ms,
        }

    # ── helpers ──────────────────────────────────────────────

    def _user_message(self, req: RunRequest) -> str:
        if req.run_type == "ingest":
            return (
                f"请处理一份新文档：document_id={req.document_id}。"
                f"先 get_schema 读规范，再 read_source 读全文，然后按工作流程更新 wiki。"
            )
        if req.run_type == "curate":
            return "请对当前 wiki 做一轮整理。先 get_schema 和 list_pages 了解现状。"
        return "请对当前 wiki 做一轮 lint。先 get_schema 和 list_pages 了解现状。"

    def _tool_message(self, tool_call_id: str, result: Any) -> dict[str, Any]:
        if isinstance(result, str):
            content = result
        else:
            content = json.dumps(result, ensure_ascii=False)
        if len(content) > self._max_tool_result_chars:
            content = content[: self._max_tool_result_chars] + "\n...(truncated)"
        return {
            "role": "tool",
            "tool_call_id": tool_call_id,
            "content": content,
        }

    def _track_counts(
        self, result: RunResult, name: str, args: dict, tool_result: Any
    ) -> None:
        """Track unique pages touched (not event count)."""
        if not isinstance(tool_result, dict) or not tool_result.get("ok"):
            return
        title = args.get("title", "")
        if not title:
            return
        if name == "create_page":
            result.created_titles.add(title)
        elif name in ("update_page", "append_page"):
            result.updated_titles.add(title)
        elif name == "delete_page":
            result.deleted_titles.add(title)

    async def _execute_tool(
        self, req: RunRequest, name: str, args: dict[str, Any]
    ) -> Any:
        api = self._api
        t, k = req.tenant_id, req.kb_id
        try:
            if name == "list_pages":
                return await api.list_pages(t, k)
            if name == "read_page":
                return await api.read_page(t, k, args["title"])
            if name == "search_pages":
                return await api.search_pages(
                    t, k, args["query"], args.get("top_k", 5)
                )
            if name == "read_source":
                return await api.read_source(t, k, args["document_id"])
            if name == "get_schema":
                schema = await api.get_schema(t, k)
                return {"schema": schema}
            if name == "create_page":
                return await api.create_page(
                    t, k, args["title"], args["content"], args.get("tags") or []
                )
            if name == "update_page":
                return await api.update_page(
                    t, k, args["title"], args["old_text"], args["new_text"]
                )
            if name == "append_page":
                return await api.append_page(t, k, args["title"], args["content"])
            if name == "delete_page":
                return await api.delete_page(t, k, args["title"])
            if name == "log_note":
                return await api.log_note(t, k, args["message"])
            return {"ok": False, "error": f"unknown tool: {name}"}
        except Exception as e:
            return {"ok": False, "error": f"{type(e).__name__}: {e}"}

    async def _write_runlog(
        self, req: RunRequest, result: RunResult, duration_ms: int
    ) -> None:
        trigger_doc = req.document_id or "(no-doc)"
        payload = {
            "tenantId": req.tenant_id,
            "kbId": req.kb_id,
            "runId": req.run_id,
            "runType": req.run_type,
            "triggerDoc": trigger_doc,
            "pagesCreated": result.pages_created,
            "pagesUpdated": result.pages_updated,
            "pagesDeleted": result.pages_deleted,
            "durationMs": duration_ms,
            "status": "success" if result.status == "success" else "error",
            "errorMessage": result.error,
            "toolCallsCount": result.tool_calls_count,
            "tokenCount": result.token_count,
            "source": req.source,
        }
        try:
            await self._api.write_runlog(payload)
        except Exception as e:
            # Don't let runlog failure mask the real run result
            log.warning("Failed to write runlog for run %s: %s", req.run_id, e)


def new_run_id() -> str:
    return f"run_{ULID()}"
