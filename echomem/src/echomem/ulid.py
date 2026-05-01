from ulid import ULID


def new() -> str:
    """Return a fresh ULID as a 26-char Crockford-Base32 string."""
    return str(ULID())
