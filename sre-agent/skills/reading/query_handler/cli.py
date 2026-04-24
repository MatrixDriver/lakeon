"""CLI: python -m skills.reading.query_handler.cli "我最近读了什么关于 X" """
from __future__ import annotations

import argparse
import logging
import os
import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(_HERE.parents[2]))  # sre-agent/

from agent_session_log import LogStore

from skills.reading.query_handler.handler import answer_question


_TEST_LLM = None


def _real_llm():
    from main import DeepseekLLMClient  # type: ignore[import-not-found]
    return DeepseekLLMClient()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Ask the reading commit log.")
    parser.add_argument("question", help="Free-form question.")
    parser.add_argument("--no-push", action="store_true", help="Print only, skip feishu DM.")
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

    home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))
    log_store = LogStore(home / "data")
    llm = _TEST_LLM if _TEST_LLM is not None else _real_llm()
    answer = answer_question(log=log_store, llm=llm, question=args.question)
    print(answer)

    if not args.no_push:
        users = os.environ.get("FEISHU_ALLOWED_USERS", "")
        parts = [u.strip() for u in users.split(",") if u.strip()]
        if parts:
            try:
                from main import feishu_send_dm  # type: ignore[import-not-found]
                feishu_send_dm(parts[0], answer)
            except Exception as exc:  # noqa: BLE001
                print(f"warning: feishu DM failed: {exc}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
