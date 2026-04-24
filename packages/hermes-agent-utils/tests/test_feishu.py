from hermes_agent_utils.feishu import jacky_open_id


def test_jacky_open_id_unset(monkeypatch):
    monkeypatch.delenv("FEISHU_ALLOWED_USERS", raising=False)
    assert jacky_open_id() is None


def test_jacky_open_id_first(monkeypatch):
    monkeypatch.setenv("FEISHU_ALLOWED_USERS", "ou_alice,ou_bob")
    assert jacky_open_id() == "ou_alice"


def test_jacky_open_id_strips_whitespace(monkeypatch):
    monkeypatch.setenv("FEISHU_ALLOWED_USERS", "  ou_jacky  ,  ou_other ")
    assert jacky_open_id() == "ou_jacky"


def test_jacky_open_id_only_whitespace(monkeypatch):
    monkeypatch.setenv("FEISHU_ALLOWED_USERS", "  ,  ")
    assert jacky_open_id() is None
