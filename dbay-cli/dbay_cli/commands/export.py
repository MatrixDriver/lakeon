"""dbay export — take all your data with you.

Exports memory, knowledge, and wiki data to local files you can read, diff, and
archive. The whole point is "在你这边" — you should be able to leave with everything
in one command.

Output layout:

  <out>/
    README.md
    METADATA.json
    memory/
      <name>_<id>/
        base.json
        memories.jsonl
        encrypted_base.json   # only if the base is encrypted
    knowledge/
      <name>_<id>/
        base.json
        documents.jsonl
"""

from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path

import typer

app = typer.Typer()

# Output top-level flag: typer doesn't support `--all` directly on a command
# because `all` is a builtin; we keep `--all` as a flag but alias internally.


def _client():
    from dbay_cli.client import DbayClient
    from dbay_cli.config import get as config_get, get_endpoint

    api_key = config_get("api_key")
    if not api_key:
        typer.echo("未找到 API key。请先运行: dbay login", err=True)
        raise typer.Exit(1)
    return DbayClient(endpoint=get_endpoint(), api_key=api_key)


_SAFE_NAME = re.compile(r"[^\w\-.]+")


def _safe_name(name: str) -> str:
    return _SAFE_NAME.sub("_", name).strip("._") or "unnamed"


def _timestamp() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S")


def _copy_local_encryption_config(base_id: str, dest: Path) -> bool:
    """Copy ~/.dbay/encrypted_bases.json entry for a base, if present."""
    cfg = Path.home() / ".dbay" / "encrypted_bases.json"
    if not cfg.exists():
        return False
    try:
        data = json.loads(cfg.read_text())
    except Exception:
        return False
    entry = data.get(base_id)
    if not entry:
        return False
    dest.write_text(json.dumps(entry, indent=2, ensure_ascii=False))
    return True


def _try_decrypt_memory(base_id: str, base_info: dict, content: str) -> str | None:
    """Best-effort decrypt one entry. Returns plaintext or None on any error."""
    try:
        from dbay_mcp.crypto import decrypt_content, get_dek
    except Exception:
        return None
    try:
        dek = get_dek(base_id, base_info.get("encrypted_dek"))
        return decrypt_content(dek, content)
    except Exception:
        return None


def _export_memory_base(client, base: dict, out_dir: Path, decrypt: bool) -> dict:
    base_id = base["id"]
    base_name = _safe_name(base.get("name") or base_id)
    base_dir = out_dir / f"{base_name}_{base_id}"
    base_dir.mkdir(parents=True, exist_ok=True)

    # base metadata
    base_info = client.get_memory_base(base_id)
    (base_dir / "base.json").write_text(
        json.dumps(base_info, indent=2, ensure_ascii=False, default=str)
    )

    # copy local encryption config if encrypted (so the dump is self-contained
    # for offline restore by the same user)
    if base.get("encrypted"):
        _copy_local_encryption_config(base_id, base_dir / "encrypted_base.json")

    # stream memories page by page
    memories_path = base_dir / "memories.jsonl"
    total = 0
    decrypted = 0
    offset = 0
    page_size = 200
    with memories_path.open("w", encoding="utf-8") as f:
        while True:
            page = client.mem_list(base_id, offset=offset, limit=page_size)
            items = page.get("memories") or page.get("items") or []
            if not items:
                break
            for m in items:
                row = dict(m)
                # for encrypted bases, keep ciphertext but also try to decrypt
                # in-place so a human (and the user on restore) can read it
                if decrypt and base.get("encrypted") and row.get("content"):
                    pt = _try_decrypt_memory(base_id, base_info, row["content"])
                    if pt is not None:
                        row["content_encrypted"] = row["content"]
                        row["content"] = pt
                        decrypted += 1
                f.write(json.dumps(row, ensure_ascii=False, default=str) + "\n")
                total += 1
            if len(items) < page_size:
                break
            offset += page_size

    return {
        "id": base_id,
        "name": base.get("name"),
        "encrypted": bool(base.get("encrypted")),
        "memories_written": total,
        "decrypted_inline": decrypted,
    }


def _export_knowledge_base(client, base: dict, out_dir: Path) -> dict:
    kb_id = base["id"]
    kb_name = _safe_name(base.get("name") or kb_id)
    kb_dir = out_dir / f"{kb_name}_{kb_id}"
    kb_dir.mkdir(parents=True, exist_ok=True)

    base_info = client.get_knowledge_base(kb_id)
    (kb_dir / "base.json").write_text(
        json.dumps(base_info, indent=2, ensure_ascii=False, default=str)
    )

    # documents: one JSONL row per document with its metadata
    docs = client.list_documents(kb_id)
    if isinstance(docs, dict):
        docs = docs.get("documents") or docs.get("items") or []
    docs_path = kb_dir / "documents.jsonl"
    with docs_path.open("w", encoding="utf-8") as f:
        for d in docs or []:
            f.write(json.dumps(d, ensure_ascii=False, default=str) + "\n")

    return {
        "id": kb_id,
        "name": base.get("name"),
        "documents_written": len(docs or []),
    }


def _write_readme(root: Path, manifest: dict) -> None:
    lines = [
        "# DBay export",
        "",
        f"Exported: {manifest['exported_at']}",
        f"Endpoint: {manifest['endpoint']}",
        "",
        "## What's in this folder",
        "",
        "- `METADATA.json` — machine-readable summary of the export.",
        "- `memory/<name>_<id>/`",
        "  - `base.json` — memory base metadata (server copy).",
        "  - `memories.jsonl` — every memory entry, one per line.",
        "  - `encrypted_base.json` — if this base is encrypted, a copy of the local",
        "    crypto config (encrypted private key + DEK). Restore uses this together",
        "    with your password — **without your password, there is no recovery**.",
        "- `knowledge/<name>_<id>/`",
        "  - `base.json` — knowledge base metadata.",
        "  - `documents.jsonl` — document metadata (one per line).",
        "",
        "## Why this exists",
        "",
        "Your memory and knowledge belong to you. This is the proof — one command,",
        "everything on your disk, in formats you can open with any tool.",
        "",
        "## What it does NOT include (yet)",
        "",
        "- Raw document file bodies stored in object storage (for now only the",
        "  metadata and extracted text are here). Full file retrieval is planned.",
        "- Database SQL dumps for Lakebase instances (use `pg_dump` directly).",
        "- Wiki entries generated by the knowledge agent (streamed into the",
        "  documents flow; a dedicated wiki export is planned).",
        "",
        "## Restoring",
        "",
        "`dbay import` (coming soon) will read this folder and recreate the bases",
        "in a fresh account. For now, the JSONL files are your archival copy — you",
        "can also diff, grep, or feed them to any other tool.",
        "",
    ]
    (root / "README.md").write_text("\n".join(lines), encoding="utf-8")


@app.command("all")
def export_all(
    out: str = typer.Option(None, "--out", "-o", help="输出目录（默认当前目录下 dbay-export-<timestamp>）"),
    decrypt: bool = typer.Option(
        True,
        "--decrypt/--no-decrypt",
        help="对本地有密钥的加密记忆库，导出时同时写入明文（默认开启）",
    ),
):
    """把你的所有 DBay 数据导出到本地。

    导出内容：memory 基础库 + memory 条目，knowledge 基础库 + 文档元数据。
    加密的 memory 基础库：密文必然保留；如果本地有密钥，会顺便把明文一起写进 JSONL
    方便人工阅读和备份。
    """
    client = _client()

    target = Path(out) if out else Path.cwd() / f"dbay-export-{_timestamp()}"
    if target.exists() and any(target.iterdir()):
        typer.echo(f"目录 {target} 已存在且非空，请换一个 --out。", err=True)
        raise typer.Exit(1)
    target.mkdir(parents=True, exist_ok=True)
    typer.echo(f"Exporting to {target} ...")

    mem_summary: list[dict] = []
    mem_bases = client.list_memory_bases() or []
    if mem_bases:
        mem_dir = target / "memory"
        mem_dir.mkdir(parents=True, exist_ok=True)
        for b in mem_bases:
            typer.echo(f"  memory · {b.get('name')} ({b['id']})")
            try:
                s = _export_memory_base(client, b, mem_dir, decrypt=decrypt)
                mem_summary.append(s)
            except Exception as e:
                typer.echo(f"    skipped: {e}", err=True)

    kb_summary: list[dict] = []
    kbs = client.list_knowledge_bases() or []
    if kbs:
        kb_dir = target / "knowledge"
        kb_dir.mkdir(parents=True, exist_ok=True)
        for b in kbs:
            typer.echo(f"  knowledge · {b.get('name')} ({b['id']})")
            try:
                s = _export_knowledge_base(client, b, kb_dir)
                kb_summary.append(s)
            except Exception as e:
                typer.echo(f"    skipped: {e}", err=True)

    from dbay_cli.config import get_endpoint

    manifest = {
        "exported_at": datetime.now(timezone.utc).isoformat(),
        "endpoint": get_endpoint(),
        "memory_bases": mem_summary,
        "knowledge_bases": kb_summary,
    }
    (target / "METADATA.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    _write_readme(target, manifest)

    total_mem = sum(s["memories_written"] for s in mem_summary)
    total_docs = sum(s["documents_written"] for s in kb_summary)
    typer.echo("")
    typer.echo(
        f"✓ Exported {len(mem_summary)} memory base(s) · {total_mem} memories · "
        f"{len(kb_summary)} knowledge base(s) · {total_docs} document record(s)"
    )
    typer.echo(f"  Location: {target}")
    typer.echo("  Your data, on your disk. 在你这边。")
