"""Evidence blobs: content-addressed, deduplicated."""
from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Optional


_MIME_EXT = {
    "text/plain": "log",
    "application/json": "json",
    "image/png": "png",
    "image/jpeg": "jpg",
    "application/x-yaml": "yaml",
    "text/markdown": "md",
}


def hash_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def ext_for_mime(mime: str) -> str:
    return _MIME_EXT.get(mime, "bin")


@dataclass(frozen=True)
class Blob:
    sha256: str
    mime: str
    size: int
    ext: str
    source: Optional[str] = None
    _bytes: bytes | None = None  # only set when created fresh (not on load)

    @classmethod
    def from_bytes(cls, data: bytes, mime: str, source: Optional[str] = None) -> "Blob":
        return cls(
            sha256=hash_bytes(data),
            mime=mime,
            size=len(data),
            ext=ext_for_mime(mime),
            source=source,
            _bytes=data,
        )

    @property
    def short_hash(self) -> str:
        return self.sha256[:8]

    @property
    def filename(self) -> str:
        return f"{self.sha256}-{self.short_hash}.{self.ext}"

    def bytes(self) -> bytes:
        if self._bytes is None:
            raise ValueError("Blob loaded from disk has no bytes; read via store")
        return self._bytes
