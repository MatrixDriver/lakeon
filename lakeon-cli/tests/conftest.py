"""
pytest 共享 fixtures 和配置
"""

import pytest


@pytest.fixture(autouse=True)
def reset_cli_state():
    """每个测试前重置 CLI 全局状态"""
    yield
    # 清理可能的全局状态
