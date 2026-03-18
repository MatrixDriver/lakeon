import json as json_lib
from rich.console import Console
from rich.table import Table

console = Console()

def print_table(rows: list[dict], columns: list[str] | None = None):
    if not rows:
        console.print("[dim]No results[/dim]")
        return
    if columns is None:
        columns = list(rows[0].keys())
    table = Table()
    for col in columns:
        table.add_column(col)
    for row in rows:
        table.add_row(*[str(row.get(col, "")) for col in columns])
    console.print(table)

def print_json(data):
    console.print(json_lib.dumps(data, indent=2, default=str))

def print_item(item: dict):
    for k, v in item.items():
        console.print(f"[bold]{k}:[/bold] {v}")
