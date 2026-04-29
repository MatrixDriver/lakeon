import json
import typer

app = typer.Typer()


def _client():
    from dbay_cli.config import get_endpoint, get as config_get
    from dbay_cli.client import DbayClient
    api_key = config_get("api_key")
    if not api_key:
        typer.echo("未找到 API key。请先运行: dbay login", err=True)
        raise typer.Exit(1)
    return DbayClient(endpoint=get_endpoint(), api_key=api_key)


def _default_mem_id(mem_id: str | None) -> str:
    """Return mem_id if given, otherwise read from config. Exit if neither."""
    if mem_id:
        return mem_id
    from dbay_cli.config import get as config_get
    default = config_get("memory_base")
    if default:
        return default
    typer.echo("未指定记忆库。请用 dbay mem use <name> 设置默认记忆库，或传入 MEM_ID 参数。", err=True)
    raise typer.Exit(1)


def _is_encrypted(mem_id: str) -> bool:
    """Check if a memory base is encrypted (has local config)."""
    from dbay_mcp.crypto import is_encrypted_base
    return is_encrypted_base(mem_id)


def _get_base_info(mem_id: str) -> dict:
    """Fetch memory base info from server."""
    return _client().get_memory_base(mem_id)


def _encrypt_for_ingest(mem_id: str, content: str, base_info: dict) -> tuple[str, list[float]]:
    """Encrypt content and generate embedding locally."""
    from dbay_mcp.crypto import get_dek, encrypt_content
    from dbay_mcp.embedding import generate_embedding
    from dbay_cli.config import get_endpoint, get as config_get

    dek = get_dek(mem_id, base_info["encrypted_dek"])
    encrypted_content = encrypt_content(dek, content)
    embedding = generate_embedding(mem_id, content, api_key=config_get("api_key"), endpoint=get_endpoint())
    return encrypted_content, embedding


def _decrypt_content(mem_id: str, encrypted_content: str, base_info: dict) -> str:
    """Decrypt content using DEK."""
    from dbay_mcp.crypto import get_dek, decrypt_content
    dek = get_dek(mem_id, base_info["encrypted_dek"])
    return decrypt_content(dek, encrypted_content)


@app.command("list")
def list_bases():
    """List memory bases."""
    from dbay_cli.config import get as config_get
    current = config_get("memory_base") or ""
    bases = _client().list_memory_bases()
    for b in bases:
        mode = "agent-extract" if b.get("one_llm_mode") else "normal"
        marker = "* " if b['id'] == current else "  "
        enc = " [encrypted]" if b.get("encrypted") else ""
        typer.echo(f"{marker}{b['id']}  {b['name']}  [{b['status']}]  mode={mode}{enc}")


def _resolve_mem(name_or_id: str) -> dict:
    """Resolve a memory base by ID or name."""
    if name_or_id.startswith("mem_"):
        return _client().get_memory_base(name_or_id)
    bases = _client().list_memory_bases()
    matches = [b for b in bases if b["name"] == name_or_id]
    if len(matches) == 1:
        return matches[0]
    if len(matches) > 1:
        typer.echo(f"Multiple memory bases named '{name_or_id}':", err=True)
        for b in matches:
            typer.echo(f"  {b['id']}  {b['name']}", err=True)
        typer.echo("Please use ID instead.", err=True)
        raise typer.Exit(1)
    typer.echo(f"Memory base '{name_or_id}' not found.", err=True)
    raise typer.Exit(1)


@app.command("use")
def use(name_or_id: str):
    """Switch default memory base. Accepts ID or name."""
    from dbay_cli.config import set as config_set
    info = _resolve_mem(name_or_id)
    config_set("memory_base", info["id"])
    typer.echo(f"Default memory base set to: {info['name']} ({info['id']})")


@app.command("create")
def create(name: str, desc: str = typer.Option(None, "--desc"),
           agent_extract: bool = typer.Option(False, "--agent-extract"),
           encrypted: bool = typer.Option(False, "--encrypted")):
    """Create a memory base. Use --encrypted for client-side encryption."""
    if encrypted:
        _create_encrypted(name, desc, agent_extract)
    else:
        result = _client().create_memory_base(name, desc, one_llm_mode=agent_extract)
        from dbay_cli.config import set as config_set
        config_set("memory_base", result["id"])
        typer.echo(json.dumps(result, indent=2, default=str))


def _create_encrypted(name: str, desc: str | None, agent_extract: bool):
    """Interactive flow for creating an encrypted memory base."""
    import base64
    import getpass
    from dbay_mcp.crypto import (
        generate_keypair, generate_dek, generate_salt,
        encrypt_private_key, encrypt_dek_with_public_key,
        save_encrypted_base, write_secret,
        load_encrypted_bases, ENCRYPTED_BASES_FILE,
    )
    from dbay_mcp.embedding import probe_embedding_dim

    typer.echo("Creating encrypted memory base...")
    typer.echo("Warning: If you lose your password, your data cannot be recovered.\n")

    # 1. Password
    password = getpass.getpass("Set encryption password: ")
    confirm = getpass.getpass("Confirm password: ")
    if password != confirm:
        typer.echo("Passwords do not match.", err=True)
        raise typer.Exit(1)

    # 2. Embedding provider
    typer.echo("\nEmbedding provider:")
    typer.echo("  1) DBay (default, uses your API key)")
    typer.echo("  2) External API (provide endpoint/key/model)")
    typer.echo("  3) Local model (requires: pip install sentence-transformers)")
    choice = typer.prompt("Choose", default="1")

    embedding_config: dict = {}
    if choice == "1":
        embedding_config = {"embedding_provider": "dbay"}
    elif choice == "2":
        embedding_config = {
            "embedding_provider": "external",
            "embedding_endpoint": typer.prompt("Embedding API endpoint"),
            "embedding_api_key": typer.prompt("Embedding API key", default=""),
            "embedding_model": typer.prompt("Embedding model name"),
        }
    elif choice == "3":
        model = typer.prompt("Model name", default="BAAI/bge-m3")
        embedding_config = {
            "embedding_provider": "local",
            "embedding_model": model,
        }
    else:
        typer.echo("Invalid choice.", err=True)
        raise typer.Exit(1)

    # 3. Generate keys
    typer.echo("\nGenerating RSA-4096 key pair...")
    private_pem, public_pem = generate_keypair()
    salt = generate_salt()
    dek = generate_dek()

    # 4. Encrypt private key with password
    encrypted_private_key = encrypt_private_key(private_pem, password, salt)

    # 5. Encrypt DEK with public key
    encrypted_dek = encrypt_dek_with_public_key(dek, public_pem)

    # 6. Probe embedding dimension
    temp_mem_id = "probe_temp"
    temp_config = {
        **embedding_config,
        "public_key": public_pem.decode("ascii"),
        "encrypted_private_key": encrypted_private_key,
        "kdf_salt": base64.b64encode(salt).decode("ascii"),
        "kdf_algorithm": "pbkdf2",
    }
    save_encrypted_base(temp_mem_id, temp_config)

    typer.echo("Probing embedding dimension...")
    from dbay_cli.config import get_endpoint, get as config_get
    try:
        dim = probe_embedding_dim(temp_mem_id, api_key=config_get("api_key"), endpoint=get_endpoint())
    except Exception as e:
        bases = load_encrypted_bases()
        bases.pop(temp_mem_id, None)
        ENCRYPTED_BASES_FILE.write_text(json.dumps(bases, indent=2) + "\n")
        typer.echo(f"Embedding probe failed: {e}", err=True)
        raise typer.Exit(1)

    typer.echo(f"Detected embedding dimension: {dim}")

    # Clean up temp entry
    bases = load_encrypted_bases()
    bases.pop(temp_mem_id, None)
    if bases:
        ENCRYPTED_BASES_FILE.write_text(json.dumps(bases, indent=2) + "\n")

    # 7. Create memory base on server
    typer.echo("Creating memory base on server...")
    result = _client().create_memory_base(
        name, desc, one_llm_mode=agent_extract,
        encrypted=True,
        encrypted_dek=encrypted_dek,
        kdf_salt=base64.b64encode(salt).decode("ascii"),
        embedding_dim=dim,
    )
    mem_id = result["id"]

    # 8. Save config locally
    config = {
        "public_key": public_pem.decode("ascii"),
        "encrypted_private_key": encrypted_private_key,
        "kdf_salt": base64.b64encode(salt).decode("ascii"),
        "kdf_algorithm": "pbkdf2",
        **embedding_config,
        "embedding_dim": dim,
    }
    save_encrypted_base(mem_id, config)

    # 9. Save password
    write_secret(password)

    # 10. Set as default memory base
    from dbay_cli.config import set as config_set
    config_set("memory_base", mem_id)

    typer.echo(f"\nEncrypted memory base created: {mem_id}")
    typer.echo(f"  Config:   ~/.dbay/encrypted_bases.json")
    typer.echo(f"  Password: ~/.dbay/secret")
    typer.echo(f"  Default:  ~/.dbay/config.json (memory_base={mem_id})")
    typer.echo(f"\nMCP will auto-detect this memory base. No additional setup needed.")
    typer.echo(f"\nTo use on another device:")
    typer.echo(f"  1. Copy ~/.dbay/encrypted_bases.json")
    typer.echo(f"  2. Create ~/.dbay/secret with DBAY_ENCRYPTION_PASSWORD=<your_password>")


@app.command("info")
def info(mem_id: str = typer.Argument(None)):
    """Show memory base details. Uses default if MEM_ID omitted."""
    mid = _default_mem_id(mem_id)
    result = _client().get_memory_base(mid)
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("delete")
def delete(mem_id: str = typer.Argument(None), yes: bool = typer.Option(False, "-y")):
    """Delete a memory base. Uses default if MEM_ID omitted."""
    mid = _default_mem_id(mem_id)
    if not yes:
        typer.confirm(f"Delete memory base {mid}?", abort=True)
    _client().delete_memory_base(mid)
    typer.echo("Deleted.")


@app.command("ingest")
def ingest(content: str, mem_id: str = typer.Option(None, "--base"),
           role: str = typer.Option("user", "--role"),
           memory_type: str = typer.Option("fact", "--type",
               help="One of: fact, decision, rejection, convention, procedural, episode."),
           source: str = typer.Option("cli", "--source",
               help="Origin tag stored on the memory (e.g. cli, claude-code, hermes-agent)."),
           raw: bool = typer.Option(False, "--raw",
               help="Treat content as raw conversation; let server extract memories. "
                    "Default stores content as a structured memory directly.")):
    """Ingest content into memory base. Uses default base if --base omitted."""
    mid = _default_mem_id(mem_id)
    signal = "conversation" if raw else "memory"
    if _is_encrypted(mid):
        info = _get_base_info(mid)
        encrypted_content, embedding = _encrypt_for_ingest(mid, content, info)
        result = _client()._request("POST", f"/memory/bases/{mid}/ingest", json={
            "content": encrypted_content,
            "signal": signal,
            "memory_type": memory_type,
            "source": source,
            "embedding": embedding,
        })
    else:
        result = _client().mem_ingest(mid, content, role,
                                      signal=signal,
                                      memory_type=memory_type,
                                      source=source)
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("ingest-extracted")
def ingest_extracted(message_id: str = typer.Option(..., "--message-id"),
                     data: str = typer.Option(..., "--data"),
                     mem_id: str = typer.Option(None, "--base")):
    """Store pre-extracted memories. Uses default base if --base omitted."""
    mid = _default_mem_id(mem_id)
    result = _client().mem_ingest_extracted(mid, message_id, json.loads(data))
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("recall")
def recall(query: str, mem_id: str = typer.Option(None, "--base"),
           types: str = typer.Option(None, "--types"),
           limit: int = typer.Option(10, "--limit")):
    """Recall memories by semantic search. Uses default base if --base omitted."""
    mid = _default_mem_id(mem_id)
    memory_types = types.split(",") if types else None
    if _is_encrypted(mid):
        from dbay_mcp.embedding import generate_embedding
        from dbay_cli.config import get_endpoint, get as config_get
        info = _get_base_info(mid)
        query_embedding = generate_embedding(mid, query, api_key=config_get("api_key"), endpoint=get_endpoint())
        body: dict = {"query_embedding": query_embedding, "top_k": limit}
        if memory_types:
            body["memory_types"] = memory_types
        result = _client()._request("POST", f"/memory/bases/{mid}/recall", json=body)
        for m in result.get("memories", []):
            try:
                content = _decrypt_content(mid, m.get("content", ""), info)
            except Exception:
                content = "[decryption failed]"
            typer.echo(f"  [{m.get('memory_type', '?')}] {content}")
    else:
        result = _client().mem_recall(mid, query, limit, memory_types)
        for m in result.get("memories", []):
            typer.echo(f"  [{m.get('memory_type', '?')}] {m.get('content', '')}")


@app.command("list-memories")
def list_memories(mem_id: str = typer.Argument(None),
                  type: str = typer.Option(None, "--type"),
                  limit: int = typer.Option(20, "--limit"),
                  offset: int = typer.Option(0, "--offset")):
    """List memories in a base. Uses default if MEM_ID omitted."""
    mid = _default_mem_id(mem_id)
    encrypted = _is_encrypted(mid)
    info = _get_base_info(mid) if encrypted else None
    result = _client().mem_list(mid, type, offset, limit)
    typer.echo(f"Total: {result.get('total', 0)}")
    for m in result.get("memories", []):
        content = m['content']
        if encrypted and info:
            try:
                content = _decrypt_content(mid, content, info)
            except Exception:
                content = "[decryption failed]"
        typer.echo(f"  #{m['id']} [{m['memory_type']}] {content}")


@app.command("delete-memory")
def delete_memory(memory_id: int, mem_id: str = typer.Option(None, "--base")):
    """Delete a single memory. Uses default base if --base omitted."""
    mid = _default_mem_id(mem_id)
    _client().mem_delete(mid, memory_id)
    typer.echo("Deleted.")


@app.command("stats")
def stats(mem_id: str = typer.Argument(None)):
    """Show memory statistics. Uses default if MEM_ID omitted."""
    mid = _default_mem_id(mem_id)
    result = _client().mem_stats(mid)
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("digest")
def digest(mem_id: str = typer.Argument(None)):
    """Run digest (reflection) on unreflected memories. Uses default if MEM_ID omitted."""
    mid = _default_mem_id(mem_id)
    result = _client().mem_digest(mid)
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("digest-extracted")
def digest_extracted(data: str = typer.Option(..., "--data"),
                     mem_id: str = typer.Option(None, "--base")):
    """Store pre-extracted digest traits. Uses default base if --base omitted."""
    mid = _default_mem_id(mem_id)
    result = _client().mem_digest_extracted(mid, json.loads(data))
    typer.echo(json.dumps(result, indent=2, default=str))


@app.command("change-password")
def change_password(mem_id: str = typer.Argument(None)):
    """Change encryption password. Uses default if MEM_ID omitted."""
    import base64
    import getpass
    from dbay_mcp.crypto import (
        load_encrypted_bases, save_encrypted_base, write_secret,
        decrypt_private_key, encrypt_private_key, generate_salt,
    )

    mid = _default_mem_id(mem_id)
    bases = load_encrypted_bases()
    if mid not in bases:
        typer.echo(f"No encryption config for {mid}", err=True)
        raise typer.Exit(1)

    config = bases[mid]
    salt = base64.b64decode(config["kdf_salt"])

    old_password = getpass.getpass("Current password: ")
    try:
        private_pem = decrypt_private_key(config["encrypted_private_key"], old_password, salt)
    except Exception:
        typer.echo("Wrong password.", err=True)
        raise typer.Exit(1)

    new_password = getpass.getpass("New password: ")
    confirm = getpass.getpass("Confirm new password: ")
    if new_password != confirm:
        typer.echo("Passwords do not match.", err=True)
        raise typer.Exit(1)

    new_salt = generate_salt()
    new_encrypted_private_key = encrypt_private_key(private_pem, new_password, new_salt)

    config["encrypted_private_key"] = new_encrypted_private_key
    config["kdf_salt"] = base64.b64encode(new_salt).decode("ascii")
    save_encrypted_base(mid, config)
    write_secret(new_password)

    typer.echo("Password changed successfully.")
