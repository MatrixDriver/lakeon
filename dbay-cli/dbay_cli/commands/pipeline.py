import typer
from dbay_cli.client import DbayClient
from dbay_cli.config import get_endpoint, get_api_key
from dbay_cli.output import print_table, print_item, console

app = typer.Typer()


def _client() -> DbayClient:
    return DbayClient(endpoint=get_endpoint(), api_key=get_api_key())


# ── Pipeline CRUD ─────────────────────────────────────────────

@app.command("list")
def list_pipelines():
    """List pipelines"""
    pipelines = _client().list_pipelines()
    print_table(pipelines, columns=["id", "name", "data_type", "latest_version", "created_at"])


@app.command("create")
def create_pipeline(
    name: str = typer.Argument(..., help="Pipeline name"),
    data_type: str = typer.Argument(..., help="Data type: TEXT, VIDEO, IMAGE, AUDIO, DOCUMENT"),
    description: str = typer.Option(None, "--desc", help="Description"),
    template_id: str = typer.Option(None, "--template", help="Source template ID"),
    dag_yaml: str = typer.Option(None, "--dag", help="DAG YAML string"),
    dag_file: str = typer.Option(None, "--dag-file", help="Path to DAG YAML file"),
):
    """Create a pipeline"""
    yaml_content = dag_yaml
    if dag_file:
        import os
        if not os.path.exists(dag_file):
            console.print(f"[red]File not found: {dag_file}[/red]")
            raise typer.Exit(1)
        with open(dag_file) as f:
            yaml_content = f.read()
    pipeline = _client().create_pipeline(
        name=name, data_type=data_type, description=description,
        source_template_id=template_id, dag_yaml=yaml_content,
    )
    print_item(pipeline)


@app.command("info")
def info_pipeline(
    pipeline_id: str = typer.Argument(..., help="Pipeline ID"),
):
    """Show pipeline details"""
    print_item(_client().get_pipeline(pipeline_id))


@app.command("delete")
def delete_pipeline(
    pipeline_id: str = typer.Argument(..., help="Pipeline ID"),
    yes: bool = typer.Option(False, "--yes", "-y", help="Skip confirmation"),
):
    """Delete a pipeline"""
    if not yes:
        typer.confirm(f"Delete pipeline {pipeline_id}?", abort=True)
    _client().delete_pipeline(pipeline_id)
    console.print(f"[green]Deleted {pipeline_id}[/green]")


@app.command("templates")
def list_templates():
    """List pipeline templates"""
    templates = _client().list_pipeline_templates()
    print_table(templates, columns=["id", "name", "data_type", "description"])


# ── Versions ──────────────────────────────────────────────────

@app.command("versions")
def list_versions(
    pipeline_id: str = typer.Argument(..., help="Pipeline ID"),
):
    """List pipeline versions"""
    versions = _client().list_pipeline_versions(pipeline_id)
    print_table(versions, columns=["version", "status", "changelog", "created_at"])


@app.command("version")
def get_version(
    pipeline_id: str = typer.Argument(..., help="Pipeline ID"),
    version: int = typer.Argument(..., help="Version number"),
):
    """Show a specific pipeline version"""
    print_item(_client().get_pipeline_version(pipeline_id, version))


@app.command("publish")
def publish_version(
    pipeline_id: str = typer.Argument(..., help="Pipeline ID"),
    dag_file: str = typer.Option(None, "--dag-file", help="Path to DAG YAML file"),
    dag_yaml: str = typer.Option(None, "--dag", help="DAG YAML string"),
    changelog: str = typer.Option(None, "--changelog", help="Version changelog"),
):
    """Publish a new pipeline version"""
    yaml_content = dag_yaml
    if dag_file:
        import os
        if not os.path.exists(dag_file):
            console.print(f"[red]File not found: {dag_file}[/red]")
            raise typer.Exit(1)
        with open(dag_file) as f:
            yaml_content = f.read()
    if not yaml_content:
        console.print("[red]Must provide --dag or --dag-file[/red]")
        raise typer.Exit(1)
    version = _client().create_pipeline_version(pipeline_id, yaml_content, changelog)
    print_item(version)


# ── Components ────────────────────────────────────────────────

@app.command("components")
def list_components():
    """List available pipeline components"""
    components = _client().list_pipeline_components()
    print_table(components, columns=["id", "name", "display_name", "category", "data_type"])


@app.command("component")
def get_component(
    component_id: str = typer.Argument(..., help="Component ID"),
):
    """Show component details"""
    print_item(_client().get_pipeline_component(component_id))


@app.command("component-versions")
def component_versions(
    component_id: str = typer.Argument(..., help="Component ID"),
):
    """List component versions"""
    versions = _client().list_component_versions(component_id)
    print_table(versions, columns=["version", "entrypoint", "execution_mode", "status", "created_at"])


# ── Runs ──────────────────────────────────────────────────────

@app.command("run")
def trigger_run(
    pipeline_id: str = typer.Argument(..., help="Pipeline ID"),
    version: int = typer.Option(..., "--version", "-v", help="Pipeline version to run"),
    dataset_id: str = typer.Option(None, "--dataset", help="Input dataset ID"),
    dataset_version: int = typer.Option(None, "--dataset-version", help="Input dataset version"),
):
    """Trigger a pipeline run"""
    run = _client().trigger_pipeline_run(
        pipeline_id, version,
        input_dataset_id=dataset_id,
        input_dataset_version=dataset_version,
    )
    print_item(run)


@app.command("run-info")
def run_info(
    run_id: str = typer.Argument(..., help="Run ID"),
):
    """Show pipeline run details"""
    print_item(_client().get_pipeline_run(run_id))


@app.command("run-steps")
def run_steps(
    run_id: str = typer.Argument(..., help="Run ID"),
):
    """List step runs for a pipeline run"""
    steps = _client().list_step_runs(run_id)
    print_table(steps, columns=["step_id", "component_id", "status", "started_at", "finished_at"])


@app.command("run-cancel")
def run_cancel(
    run_id: str = typer.Argument(..., help="Run ID"),
):
    """Cancel a pipeline run"""
    _client().cancel_pipeline_run(run_id)
    console.print(f"[green]Cancelled run {run_id}[/green]")
