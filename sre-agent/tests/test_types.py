import re
import time
from agent_session_log.ids import new_session_id


def test_session_id_format():
    sid = new_session_id()
    assert re.match(r"^sess_\d{8}T\d{6}_[a-f0-9]{6}$", sid), sid


def test_session_id_unique():
    ids = {new_session_id() for _ in range(100)}
    assert len(ids) == 100


def test_session_id_timestamp_monotonic():
    a = new_session_id()
    time.sleep(0.001)
    b = new_session_id()
    # Compare timestamp portions
    assert a.split("_")[1] <= b.split("_")[1]
