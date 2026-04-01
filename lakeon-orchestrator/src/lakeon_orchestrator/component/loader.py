from __future__ import annotations

import importlib
from typing import Any, Callable


class ComponentLoader:
    """Dynamically load component functions from entrypoint paths.

    Entrypoint format: "module.path.function_name"
    e.g. "lakeon.components.video.scene_split.video_scene_split"
    """

    _cache: dict[str, Callable] = {}

    @classmethod
    def load(cls, entrypoint: str) -> Callable:
        """Load and cache a component function by its dotted entrypoint path."""
        if entrypoint in cls._cache:
            return cls._cache[entrypoint]

        module_path, _, func_name = entrypoint.rpartition(".")
        if not module_path:
            raise ImportError(f"Invalid entrypoint '{entrypoint}': must be 'module.function'")

        module = importlib.import_module(module_path)
        func = getattr(module, func_name)

        cls._cache[entrypoint] = func
        return func

    @classmethod
    def clear_cache(cls) -> None:
        cls._cache.clear()
