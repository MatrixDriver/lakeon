import json
import sys
import time
import typer
from typing import Optional
from dbay_cli.client import DbayClient
from dbay_cli.config import get_endpoint, get_api_key
from dbay_cli.output import print_table, print_item, console

app = typer.Typer(help="Data lake job management")


def _client() -> DbayClient:
    return DbayClient(endpoint=get_endpoint(), api_key=get_api_key())


@app.command("submit")
def submit(
    name: str = typer.Option(..., "--name", "-n", help="Job name"),
    type: str = typer.Option(..., "--type", "-t", help="Job type: python | ray | finetune"),
    entrypoint: str = typer.Option(None, "--entrypoint", "-e", help="Entrypoint command"),
    image_key: str = typer.Option(None, "--image-key", help="Preset image key (python-slim, python-data, ray, ray-gpu)"),
    requirements: str = typer.Option(None, "--requirements", help="pip requirements (comma-separated)"),
    cpu: str = typer.Option(None, "--cpu", help="CPU request (e.g. '1')"),
    memory: str = typer.Option(None, "--memory", help="Memory request (e.g. '2Gi')"),
    timeout: int = typer.Option(None, "--timeout", help="Timeout seconds"),
    wait: bool = typer.Option(False, "--wait", "-w", help="Wait for job to complete"),
    wait_timeout: int = typer.Option(300, "--wait-timeout", help="Max seconds to wait"),
):
    """Submit a data lake job."""
    job_type = type.upper()
    if job_type not in ("PYTHON", "RAY", "FINETUNE"):
        console.print(f"[red]Invalid type '{type}'. Must be python, ray, or finetune.[/red]")
        raise typer.Exit(1)

    body: dict = {"name": name, "type": job_type}
    if entrypoint:
        body["entrypoint"] = entrypoint
    if image_key:
        body["image_key"] = image_key
    if requirements:
        body["requirements"] = requirements
    if timeout:
        body["timeout_seconds"] = timeout
    if cpu or memory:
        body["resources"] = {}
        if cpu:
            body["resources"]["cpu"] = cpu
        if memory:
            body["resources"]["memory"] = memory

    job = _client().submit_datalake_job(body)
    print_item(job)

    if wait:
        job_id = job["id"]
        console.print(f"\n[dim]Waiting for job {job_id}...[/dim]")
        result = _poll_until_terminal(_client(), job_id, wait_timeout)
        console.print(f"\nFinal status: [bold]{result['status']}[/bold]")
        if result.get("error_message"):
            console.print(f"[red]Error: {result['error_message']}[/red]")


@app.command("list")
def list_jobs(
    status: Optional[str] = typer.Option(None, "--status", "-s", help="Filter by status"),
):
    """List data lake jobs."""
    jobs = _client().list_datalake_jobs(status=status)
    print_table(jobs, columns=["id", "name", "type", "status", "created_at"])


@app.command("info")
def info(
    job_id: str = typer.Argument(..., help="Job ID"),
):
    """Show job details."""
    job = _client().get_datalake_job(job_id)
    print_item(job)


@app.command("cancel")
def cancel(
    job_id: str = typer.Argument(..., help="Job ID"),
    yes: bool = typer.Option(False, "--yes", "-y", help="Skip confirmation"),
):
    """Cancel a running or pending job."""
    if not yes:
        typer.confirm(f"Cancel job {job_id}?", abort=True)
    _client().cancel_datalake_job(job_id)
    console.print(f"[green]Job {job_id} cancelled.[/green]")


@app.command("logs")
def logs(
    job_id: str = typer.Argument(..., help="Job ID"),
):
    """Stream job logs (SSE)."""
    import httpx
    endpoint = get_endpoint().rstrip("/")
    api_key = get_api_key()
    url = f"{endpoint}/api/v1/datalake/jobs/{job_id}/logs"
    headers = {"Authorization": f"Bearer {api_key}", "Accept": "text/event-stream"}
    try:
        with httpx.Client(verify=False, timeout=None) as http:
            with http.stream("GET", url, headers=headers) as resp:
                if resp.status_code >= 400:
                    console.print(f"[red]Error: HTTP {resp.status_code}[/red]")
                    raise typer.Exit(1)
                for line in resp.iter_lines():
                    if line.startswith("data:"):
                        data = line[5:].strip()
                        if data:
                            console.print(data)
    except KeyboardInterrupt:
        pass


@app.command("wait")
def wait_cmd(
    job_id: str = typer.Argument(..., help="Job ID"),
    timeout: int = typer.Option(300, "--timeout", "-t", help="Max seconds to wait"),
    interval: float = typer.Option(3.0, "--interval", help="Poll interval seconds"),
):
    """Wait for a job to reach a terminal state."""
    result = _poll_until_terminal(_client(), job_id, timeout, interval)
    status = result["status"]
    if status == "SUCCEEDED":
        console.print(f"[green]✓ Job {job_id} SUCCEEDED[/green]")
    elif status == "FAILED":
        console.print(f"[red]✗ Job {job_id} FAILED[/red]")
        if result.get("error_message"):
            console.print(f"[red]  {result['error_message']}[/red]")
        raise typer.Exit(1)
    elif status == "CANCELLED":
        console.print(f"[yellow]⊘ Job {job_id} CANCELLED[/yellow]")
        raise typer.Exit(2)
    else:
        console.print(f"[yellow]Job {job_id} still in status: {status}[/yellow]")
        raise typer.Exit(3)


def _poll_until_terminal(client: DbayClient, job_id: str, timeout: int = 300,
                          interval: float = 3.0) -> dict:
    terminal = {"SUCCEEDED", "FAILED", "CANCELLED"}
    deadline = time.time() + timeout
    last_status = None
    while time.time() < deadline:
        job = client.get_datalake_job(job_id)
        status = job["status"]
        if status != last_status:
            console.print(f"  [dim]{status}[/dim]")
            last_status = status
        if status in terminal:
            return job
        time.sleep(interval)
    raise typer.BadParameter(f"Job {job_id} did not complete within {timeout}s")
