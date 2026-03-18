import typer
from dbay_cli.client import DbayClient
from dbay_cli.config import get_endpoint, get_api_key
from dbay_cli.output import print_table, print_item, console

app = typer.Typer()

def _client() -> DbayClient:
    return DbayClient(endpoint=get_endpoint(), api_key=get_api_key())

@app.command("list")
def list_kbs():
    """List knowledge bases"""
    kbs = _client().list_knowledge_bases()
    print_table(kbs, columns=["id", "name", "status", "document_count", "created_at"])

@app.command("create")
def create_kb(
    name: str = typer.Argument(..., help="Knowledge base name"),
    description: str = typer.Option(None, "--desc", help="Description"),
):
    """Create a knowledge base"""
    kb = _client().create_knowledge_base(name, description)
    print_item(kb)

@app.command("info")
def info_kb(
    kb_id: str = typer.Argument(..., help="Knowledge base ID"),
):
    """Show knowledge base details"""
    kb = _client().get_knowledge_base(kb_id)
    print_item(kb)

@app.command("delete")
def delete_kb(
    kb_id: str = typer.Argument(..., help="Knowledge base ID"),
    yes: bool = typer.Option(False, "--yes", "-y", help="Skip confirmation"),
):
    """Delete a knowledge base"""
    if not yes:
        typer.confirm(f"Delete knowledge base {kb_id}?", abort=True)
    _client().delete_knowledge_base(kb_id)
    console.print(f"[green]Deleted {kb_id}[/green]")

@app.command("upload")
def upload_doc(
    kb_id: str = typer.Argument(..., help="Knowledge base ID"),
    file_path: str = typer.Argument(..., help="Path to file (PDF/DOCX/MD)"),
):
    """Upload a document to a knowledge base"""
    import os
    import httpx

    filename = os.path.basename(file_path)
    if not os.path.exists(file_path):
        console.print(f"[red]File not found: {file_path}[/red]")
        raise typer.Exit(1)

    client = _client()

    # 1. Get presigned URL
    console.print(f"Getting upload URL for {filename}...")
    result = client.get_upload_url(kb_id, filename)
    doc_id = result["document_id"]
    upload_url = result["upload_url"]

    # 2. Upload file
    console.print(f"Uploading {filename}...")
    with open(file_path, "rb") as f:
        resp = httpx.put(upload_url, content=f.read(), timeout=120, verify=False)
        if resp.status_code not in (200, 201):
            console.print(f"[red]Upload failed: {resp.status_code}[/red]")
            raise typer.Exit(1)

    # 3. Trigger processing
    console.print(f"Triggering processing for {doc_id}...")
    client.process_document(doc_id)
    console.print(f"[green]Document uploaded and processing started: {doc_id}[/green]")

@app.command("docs")
def list_docs(
    kb_id: str = typer.Argument(..., help="Knowledge base ID"),
):
    """List documents in a knowledge base"""
    docs = _client().list_documents(kb_id)
    print_table(docs, columns=["id", "filename", "format", "status", "chunks_count", "created_at"])

@app.command("search")
def search(
    kb_id: str = typer.Argument(..., help="Knowledge base ID"),
    query: str = typer.Argument(..., help="Search query"),
    top_k: int = typer.Option(5, "--top-k", "-k", help="Number of results"),
):
    """Search a knowledge base"""
    result = _client().search_knowledge(kb_id, query, top_k)
    results = result.get("results", [])
    if not results:
        console.print("[yellow]No results found[/yellow]")
        return
    for i, r in enumerate(results):
        meta = r.get("metadata", {})
        console.print(f"\n[bold]#{i+1}[/bold] (score: {r.get('score', 0):.3f})")
        if meta.get("filename"):
            console.print(f"  [dim]Source: {meta['filename']}", end="")
            if meta.get("section"):
                console.print(f" > {meta['section']}", end="")
            console.print("[/dim]")
        console.print(f"  {r.get('content', '')[:200]}")
