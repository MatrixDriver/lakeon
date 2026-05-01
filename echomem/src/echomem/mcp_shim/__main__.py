from __future__ import annotations

import asyncio
import json
import sys

from echomem.config import load_config
from echomem.mcp_shim.shim import handle_message


async def _serve() -> None:
    cfg = load_config()
    base_url = f"http://{cfg.host}:{cfg.port}"
    loop = asyncio.get_running_loop()
    reader = asyncio.StreamReader()
    protocol = asyncio.StreamReaderProtocol(reader)
    await loop.connect_read_pipe(lambda: protocol, sys.stdin)

    while True:
        line = await reader.readline()
        if not line:
            break
        try:
            msg = json.loads(line.decode("utf-8"))
        except json.JSONDecodeError:
            continue
        out = await handle_message(msg, base_url=base_url)
        if out is None:
            continue
        sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
        sys.stdout.flush()


def main() -> None:
    asyncio.run(_serve())


if __name__ == "__main__":
    main()
