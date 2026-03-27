"""DBay CLI — `dbay login` / `dbay setup` / `dbay status`."""

import json
import sys
from getpass import getpass
from pathlib import Path

import httpx
import yaml

CONFIG_DIR = Path.home() / ".dbay"
CONFIG_FILE = CONFIG_DIR / "config.json"
DEFAULT_ENDPOINT = "https://api.dbay.cloud:8443"


def _load_config() -> dict:
    if CONFIG_FILE.exists():
        return json.loads(CONFIG_FILE.read_text())
    return {}


def _save_config(cfg: dict) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_FILE.write_text(json.dumps(cfg, indent=2) + "\n")
    CONFIG_FILE.chmod(0o600)
    print(f"Config saved to {CONFIG_FILE}")


def _verify_key(endpoint: str, api_key: str) -> bool:
    """Verify API key by calling a lightweight endpoint."""
    try:
        resp = httpx.get(
            f"{endpoint}/api/v1/knowledge/bases",
            headers={"Authorization": f"Bearer {api_key}"},
            verify=False,
            timeout=10,
        )
        return resp.status_code == 200
    except Exception as e:
        print(f"Connection error: {e}")
        return False


def _detect_resources(endpoint: str, api_key: str) -> tuple[str | None, str | None]:
    """Auto-detect default knowledge base and memory base."""
    headers = {"Authorization": f"Bearer {api_key}"}
    kb_id = None
    mem_id = None
    try:
        resp = httpx.get(f"{endpoint}/api/v1/knowledge/bases", headers=headers, verify=False, timeout=10)
        if resp.status_code == 200:
            bases = resp.json()
            if len(bases) == 1:
                kb_id = bases[0]["id"]
                print(f"  Knowledge base: {bases[0].get('name', '?')} ({kb_id})")
            elif bases:
                print(f"  {len(bases)} knowledge bases found — set manually if needed")
    except Exception:
        pass
    try:
        resp = httpx.get(f"{endpoint}/api/v1/memory/bases", headers=headers, verify=False, timeout=10)
        if resp.status_code == 200:
            bases = resp.json()
            ready = [b for b in bases if b.get("status") == "READY"]
            if len(ready) == 1:
                mem_id = ready[0]["id"]
                print(f"  Memory base:    {ready[0].get('name', '?')} ({mem_id})")
            elif ready:
                print(f"  {len(ready)} memory bases found — set manually if needed")
    except Exception:
        pass
    return kb_id, mem_id


def cmd_login() -> None:
    """Interactive login: verify API key and write ~/.dbay/config.json."""
    cfg = _load_config()

    # Check if new user or existing
    current_key = cfg.get("api_key", "")
    if not current_key:
        print("Welcome to DBay!")
        print("If you don't have an account yet, sign up at: https://dbay.cloud")
        print("Your API Key is on the dashboard after login.\n")

    # Endpoint
    current_ep = cfg.get("endpoint", DEFAULT_ENDPOINT)
    ep_input = input(f"Endpoint [{current_ep}]: ").strip()
    endpoint = ep_input or current_ep

    # API key
    masked = f"{current_key[:6]}...{current_key[-4:]}" if len(current_key) > 10 else ""
    prompt = f"API Key [{masked}]: " if masked else "API Key: "
    key_input = getpass(prompt).strip()
    api_key = key_input or current_key

    if not api_key:
        print("Error: API key is required. Get yours at https://dbay.cloud")
        sys.exit(1)

    # Verify
    print("Verifying...", end=" ", flush=True)
    if not _verify_key(endpoint, api_key):
        print("FAILED")
        print("API key verification failed. Check your key and endpoint.")
        print("Get a valid key at: https://dbay.cloud")
        sys.exit(1)
    print("OK")

    # Auto-detect resources
    print("Detecting resources...")
    kb_id, mem_id = _detect_resources(endpoint, api_key)

    # Build config
    new_cfg: dict = {"endpoint": endpoint, "api_key": api_key}
    if kb_id:
        new_cfg["knowledge_base"] = kb_id
    elif cfg.get("knowledge_base"):
        new_cfg["knowledge_base"] = cfg["knowledge_base"]
    if mem_id:
        new_cfg["memory_base"] = mem_id
    elif cfg.get("memory_base"):
        new_cfg["memory_base"] = cfg["memory_base"]

    _save_config(new_cfg)
    print("\nDone! Next steps:")
    print("  1. Register MCP server:  claude mcp add --scope user dbay -- uvx dbay-mcp")
    print("  2. Enable memory hints:  dbay setup claude-code")
    print("     (also available: dbay setup gemini | cursor | windsurf)")


def cmd_status() -> None:
    """Show current config status."""
    cfg = _load_config()
    if not cfg:
        print("Not configured. Run: dbay login")
        return
    print(f"Config:    {CONFIG_FILE}")
    print(f"Endpoint:  {cfg.get('endpoint', '(not set)')}")
    key = cfg.get("api_key", "")
    print(f"API Key:   {key[:6]}...{key[-4:]}" if len(key) > 10 else "API Key:   (not set)")
    print(f"KB:        {cfg.get('knowledge_base', '(auto)')}")
    print(f"Memory:    {cfg.get('memory_base', '(auto)')}")


def _load_descs() -> dict:
    descs_file = Path(__file__).parent / "tool_descriptions.yaml"
    if descs_file.exists():
        return yaml.safe_load(descs_file.read_text()) or {}
    return {}


def cmd_setup(agent_name: str) -> None:
    """Inject DBay memory instructions into an agent's instruction file."""
    descs = _load_descs()
    agents = descs.get("agents", {})

    if agent_name not in agents:
        available = ", ".join(sorted(agents.keys()))
        print(f"Unknown agent: {agent_name}")
        print(f"Available: {available}")
        sys.exit(1)

    agent = agents[agent_name]
    target = Path(agent["file"]).expanduser()
    marker = agent["marker"]
    instruction = agent["instruction"]

    # Check if already injected
    if target.exists() and marker in target.read_text():
        print(f"Already configured: {target}")
        return

    # Append instruction
    target.parent.mkdir(parents=True, exist_ok=True)
    with open(target, "a") as f:
        if target.exists() and target.stat().st_size > 0:
            f.write("\n")
        f.write(instruction)

    print(f"Done: {target}")


def main() -> None:
    args = sys.argv[1:]
    if not args or args[0] == "login":
        cmd_login()
    elif args[0] == "status":
        cmd_status()
    elif args[0] == "setup":
        if len(args) < 2:
            descs = _load_descs()
            available = ", ".join(sorted(descs.get("agents", {}).keys()))
            print(f"Usage: dbay setup <agent>")
            print(f"Available agents: {available}")
            sys.exit(1)
        cmd_setup(args[1])
    else:
        print("Usage: dbay [login|status|setup <agent>]")
        sys.exit(1)


if __name__ == "__main__":
    main()
