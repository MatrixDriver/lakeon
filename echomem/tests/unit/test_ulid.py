import re
from echomem.ulid import new


def test_new_returns_26_char_string():
    v = new()
    assert isinstance(v, str)
    assert len(v) == 26
    assert re.fullmatch(r"[0-9A-HJKMNP-TV-Z]+", v)


def test_new_is_unique():
    assert len({new() for _ in range(100)}) == 100


def test_new_is_lexicographic():
    a = new()
    b = new()
    assert a <= b  # ULIDs are time-ordered
