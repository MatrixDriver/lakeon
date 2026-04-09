import typer

app = typer.Typer(name="dbay", help="DBay Serverless PostgreSQL CLI")

from dbay_cli.commands import auth, db, branch, version, user, kb, datalake, mem, setup, pipeline

app.add_typer(auth.app, name="config", help="CLI configuration")
app.add_typer(db.app, name="db", help="Database management")
app.add_typer(branch.app, name="branch", help="Branch management")
app.add_typer(version.app, name="version", help="Version management")
app.add_typer(user.app, name="user", help="Database user management")
app.add_typer(kb.app, name="kb", help="Knowledge base management")
app.add_typer(datalake.app, name="datalake", help="Data lake job management")
app.add_typer(mem.app, name="mem", help="Memory base management")
app.add_typer(setup.app, name="setup", help="Setup AI agent integration")
app.add_typer(pipeline.app, name="pipeline", help="Pipeline management")

def _oauth_login(provider: str, endpoint: str) -> dict:
    """Open browser for OAuth login, wait for callback."""
    import webbrowser
    from http.server import HTTPServer, BaseHTTPRequestHandler
    from urllib.parse import urlparse, parse_qs, quote
    import httpx

    auth_code = None

    class CallbackHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            nonlocal auth_code
            query = parse_qs(urlparse(self.path).query)
            auth_code = query.get("code", [None])[0]

            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            if auth_code:
                self.wfile.write(
                    b"<html><body style='font-family:sans-serif;padding:2rem'>"
                    b"<h2>Login successful</h2>"
                    b"<p>You can close this tab and return to the terminal.</p>"
                    b"</body></html>"
                )
            else:
                error = query.get("error", ["unknown"])[0]
                self.wfile.write(
                    f"<html><body style='font-family:sans-serif;padding:2rem'>"
                    f"<h2>Login failed</h2><p>{error}</p>"
                    f"</body></html>".encode()
                )

        def log_message(self, format, *args):
            pass  # Suppress server logs

    # Start server on a random available port
    server = HTTPServer(("127.0.0.1", 0), CallbackHandler)
    port = server.server_address[1]
    redirect_uri = f"http://localhost:{port}/callback"

    # Build OAuth URL
    oauth_url = f"{endpoint}/api/v1/auth/oauth/{provider}?redirect_uri={quote(redirect_uri)}"

    typer.echo(f"Opening browser for {provider.capitalize()} login...")
    typer.echo(f"If browser does not open, visit: {oauth_url}")
    webbrowser.open(oauth_url)

    # Wait for exactly one callback request (120 second timeout)
    server.timeout = 120
    server.handle_request()
    server.server_close()

    if not auth_code:
        typer.echo("Login failed: no auth code received.", err=True)
        raise typer.Exit(1)

    # Exchange auth code for API key
    with httpx.Client(verify=False, timeout=30) as http:
        resp = http.post(
            f"{endpoint}/api/v1/auth/oauth/token",
            json={"code": auth_code},
            headers={"Content-Type": "application/json"},
        )
        if resp.status_code != 200:
            typer.echo(f"Login failed: {resp.text}", err=True)
            raise typer.Exit(1)
        return resp.json()


@app.command()
def login(
    google: bool = typer.Option(False, "--google", help="Login with Google"),
    github: bool = typer.Option(False, "--github", help="Login with GitHub"),
    username: str = typer.Option(None, prompt=False),
    password: str = typer.Option(None, prompt=False, hide_input=True),
):
    """Login and save API key."""
    from dbay_cli.config import get_endpoint, set as config_set

    if google or github:
        provider = "google" if google else "github"
        result = _oauth_login(provider, get_endpoint())
    else:
        if not username:
            username = typer.prompt("Username")
        if not password:
            password = typer.prompt("Password", hide_input=True)
        from dbay_cli.client import DbayClient
        client = DbayClient(endpoint=get_endpoint())
        result = client.login(username, password)

    config_set("api_key", result["api_key"])
    typer.echo(f"Logged in as {result.get('username') or result.get('name', 'user')}")

if __name__ == "__main__":
    app()
