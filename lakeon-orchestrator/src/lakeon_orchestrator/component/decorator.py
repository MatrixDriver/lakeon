from __future__ import annotations

import functools
from typing import Any, Callable, Optional

_COMPONENT_META_ATTR = "__component_meta__"


def Component(
    name: str,
    display_name: str,
    category: str,
    data_type: str,
    params_schema: Optional[dict[str, Any]] = None,
    input_schema: Optional[dict[str, Any]] = None,
    output_schema: Optional[dict[str, Any]] = None,
    output_branches: Optional[list[str]] = None,
    requires_gpu: bool = False,
    requires_model: Optional[str] = None,
    execution_mode: str = "FUNCTION",
) -> Callable:
    """Decorator that attaches component metadata to a function."""

    def decorator(func: Callable) -> Callable:
        meta = {
            "name": name,
            "display_name": display_name,
            "category": category,
            "data_type": data_type,
            "params_schema": params_schema or {},
            "input_schema": input_schema or {},
            "output_schema": output_schema or {},
            "output_branches": output_branches or [],
            "requires_gpu": requires_gpu,
            "requires_model": requires_model,
            "execution_mode": execution_mode,
        }
        setattr(func, _COMPONENT_META_ATTR, meta)

        @functools.wraps(func)
        def wrapper(*args, **kwargs):
            return func(*args, **kwargs)

        setattr(wrapper, _COMPONENT_META_ATTR, meta)
        return wrapper

    return decorator


def get_component_meta(func: Callable) -> dict[str, Any]:
    """Retrieve component metadata from a decorated function."""
    meta = getattr(func, _COMPONENT_META_ATTR, None)
    if meta is None:
        raise ValueError(f"'{func.__name__}' is not a registered component (missing @Component decorator)")
    return meta
