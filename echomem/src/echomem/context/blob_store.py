from __future__ import annotations

import hashlib
from pathlib import Path


class BlobStore:
    """Content-addressable file store. Layout: <root>/blobs/<sha[:2]>/<sha>."""

    def __init__(self, root: Path):
        self.root = Path(root)
        self.blobs_dir = self.root / "blobs"
        self.blobs_dir.mkdir(parents=True, exist_ok=True)

    def path_for(self, sha256: str) -> Path:
        return self.blobs_dir / sha256[:2] / sha256

    def exists(self, sha256: str) -> bool:
        return self.path_for(sha256).exists()

    def write(self, content: bytes) -> str:
        sha = hashlib.sha256(content).hexdigest()
        p = self.path_for(sha)
        if not p.exists():
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(content)
        return sha

    def read(self, sha256: str) -> bytes:
        p = self.path_for(sha256)
        if not p.exists():
            raise FileNotFoundError(f"blob not found: {sha256}")
        return p.read_bytes()
