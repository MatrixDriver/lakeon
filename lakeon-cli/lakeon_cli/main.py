"""
LakeOn CLI 主入口

使用 Typer 框架构建命令行工具，管理 Serverless PostgreSQL 实例。
"""

import typer

from lakeon_cli.commands import db, branch, tenant, config

app = typer.Typer(
    name="lakeon",
    help="LakeOn Serverless PostgreSQL CLI",
    no_args_is_help=True,
)

app.add_typer(db.app, name="db", help="数据库实例管理")
app.add_typer(branch.app, name="branch", help="分支管理")
app.add_typer(tenant.app, name="tenant", help="租户管理")
app.add_typer(config.app, name="config", help="CLI 配置")

if __name__ == "__main__":
    app()
