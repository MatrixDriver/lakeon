"""Test configuration for unit tests of pipeline components."""

import sys
from pathlib import Path

# Add the components directory to sys.path so tests can import from it
_components_root = Path(__file__).resolve().parent.parent.parent / "components"
if str(_components_root.parent) not in sys.path:
    sys.path.insert(0, str(_components_root.parent))
