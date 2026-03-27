"""Entry point for `python -m dbay_mcp` and `uvx dbay-mcp`."""
from dbay_mcp.server import mcp


def main() -> None:
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
