"""
Client-side encryption module for DBay memory.

Three-factor key hierarchy:
1. Password (in ~/.dbay/secret) -> Scrypt -> derived key -> decrypts private_key
2. Config file (~/.dbay/encrypted_bases.json) -> encrypted_private_key + public_key
3. Server (memory_bases table) -> encrypted_dek (RSA-encrypted)
"""

from __future__ import annotations

import base64
import json
import os
from pathlib import Path
from typing import Optional

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

_DBAY_DIR = Path.home() / ".dbay"
_SECRET_FILE = _DBAY_DIR / "secret"
_ENCRYPTED_BASES_FILE = _DBAY_DIR / "encrypted_bases.json"

# Public alias for external consumers (e.g. tests, CLI)
ENCRYPTED_BASES_FILE = _ENCRYPTED_BASES_FILE

_SCRYPT_N = 2**17
_SCRYPT_R = 8
_SCRYPT_P = 1
_SCRYPT_KEY_LENGTH = 32  # 256 bits

_AES_NONCE_LENGTH = 12  # 96 bits for AES-GCM

# ---------------------------------------------------------------------------
# Runtime DEK cache
# ---------------------------------------------------------------------------

_dek_cache: dict[str, bytes] = {}

# ---------------------------------------------------------------------------
# Key generation
# ---------------------------------------------------------------------------


def generate_keypair() -> tuple[bytes, bytes]:
    """Generate an RSA-4096 keypair. Returns (private_pem, public_pem)."""
    private_key = rsa.generate_private_key(
        public_exponent=65537,
        key_size=4096,
    )
    private_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    public_pem = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return private_pem, public_pem


def generate_dek() -> bytes:
    """Generate a random 256-bit data encryption key."""
    return os.urandom(32)


def generate_salt() -> bytes:
    """Generate a random 16-byte salt for Scrypt."""
    return os.urandom(16)


# ---------------------------------------------------------------------------
# Password-based key derivation
# ---------------------------------------------------------------------------


def derive_key_from_password(password: str, salt: bytes) -> bytes:
    """Derive a 256-bit key from a password using Scrypt."""
    kdf = Scrypt(
        salt=salt,
        length=_SCRYPT_KEY_LENGTH,
        n=_SCRYPT_N,
        r=_SCRYPT_R,
        p=_SCRYPT_P,
    )
    return kdf.derive(password.encode("utf-8"))


# ---------------------------------------------------------------------------
# Private key encryption (password -> AES-GCM -> private key)
# ---------------------------------------------------------------------------


def encrypt_private_key(private_pem: bytes, password: str, salt: bytes) -> str:
    """
    Encrypt a PEM-encoded private key with a password-derived key.
    Returns base64(nonce + ciphertext).
    """
    derived = derive_key_from_password(password, salt)
    nonce = os.urandom(_AES_NONCE_LENGTH)
    aesgcm = AESGCM(derived)
    ciphertext = aesgcm.encrypt(nonce, private_pem, None)
    return base64.b64encode(nonce + ciphertext).decode("ascii")


def decrypt_private_key(encrypted_b64: str, password: str, salt: bytes) -> bytes:
    """
    Decrypt a PEM-encoded private key from base64(nonce + ciphertext).
    """
    raw = base64.b64decode(encrypted_b64)
    nonce = raw[:_AES_NONCE_LENGTH]
    ciphertext = raw[_AES_NONCE_LENGTH:]
    derived = derive_key_from_password(password, salt)
    aesgcm = AESGCM(derived)
    return aesgcm.decrypt(nonce, ciphertext, None)


# ---------------------------------------------------------------------------
# DEK encryption (RSA-OAEP)
# ---------------------------------------------------------------------------


def encrypt_dek_with_public_key(dek: bytes, public_pem: bytes) -> str:
    """Encrypt a DEK with an RSA public key. Returns base64-encoded ciphertext."""
    public_key = serialization.load_pem_public_key(public_pem)
    encrypted = public_key.encrypt(  # type: ignore[union-attr]
        dek,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )
    return base64.b64encode(encrypted).decode("ascii")


def decrypt_dek_with_private_key(encrypted_dek_b64: str, private_pem: bytes) -> bytes:
    """Decrypt a DEK with an RSA private key."""
    private_key = serialization.load_pem_private_key(private_pem, password=None)
    encrypted = base64.b64decode(encrypted_dek_b64)
    return private_key.decrypt(  # type: ignore[union-attr]
        encrypted,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )


# ---------------------------------------------------------------------------
# Content encryption (AES-256-GCM)
# ---------------------------------------------------------------------------


def encrypt_content(dek: bytes, plaintext: str) -> str:
    """Encrypt plaintext with a DEK. Returns base64(nonce + ciphertext)."""
    nonce = os.urandom(_AES_NONCE_LENGTH)
    aesgcm = AESGCM(dek)
    ciphertext = aesgcm.encrypt(nonce, plaintext.encode("utf-8"), None)
    return base64.b64encode(nonce + ciphertext).decode("ascii")


def decrypt_content(dek: bytes, encrypted_b64: str) -> str:
    """Decrypt content from base64(nonce + ciphertext)."""
    raw = base64.b64decode(encrypted_b64)
    nonce = raw[:_AES_NONCE_LENGTH]
    ciphertext = raw[_AES_NONCE_LENGTH:]
    aesgcm = AESGCM(dek)
    return aesgcm.decrypt(nonce, ciphertext, None).decode("utf-8")


# ---------------------------------------------------------------------------
# Config file management
# ---------------------------------------------------------------------------


def _read_password() -> Optional[str]:
    """
    Read the encryption password.
    Checks DBAY_ENCRYPTION_PASSWORD env var first, then ~/.dbay/secret file.
    """
    env_pw = os.environ.get("DBAY_ENCRYPTION_PASSWORD")
    if env_pw:
        return env_pw

    if not _SECRET_FILE.exists():
        return None

    # The secret file may contain other lines; the password is on the first line
    text = _SECRET_FILE.read_text(encoding="utf-8").strip()
    if not text:
        return None

    # First line is the password
    return text.split("\n")[0].strip()


def write_secret(password: str) -> None:
    """Write the encryption password to ~/.dbay/secret with chmod 600."""
    _DBAY_DIR.mkdir(parents=True, exist_ok=True)

    # Preserve other lines if the file already exists
    other_lines: list[str] = []
    if _SECRET_FILE.exists():
        lines = _SECRET_FILE.read_text(encoding="utf-8").strip().split("\n")
        if len(lines) > 1:
            other_lines = lines[1:]

    content = password
    if other_lines:
        content = password + "\n" + "\n".join(other_lines)

    _SECRET_FILE.write_text(content + "\n", encoding="utf-8")
    _SECRET_FILE.chmod(0o600)


def load_encrypted_bases() -> dict:
    """Read ~/.dbay/encrypted_bases.json. Returns empty dict if not found."""
    if not _ENCRYPTED_BASES_FILE.exists():
        return {}
    text = _ENCRYPTED_BASES_FILE.read_text(encoding="utf-8")
    if not text.strip():
        return {}
    return json.loads(text)


def save_encrypted_base(mem_id: str, config: dict) -> None:
    """Add or update an entry in encrypted_bases.json, chmod 600."""
    _DBAY_DIR.mkdir(parents=True, exist_ok=True)
    bases = load_encrypted_bases()
    bases[mem_id] = config
    _ENCRYPTED_BASES_FILE.write_text(
        json.dumps(bases, indent=2) + "\n", encoding="utf-8"
    )
    _ENCRYPTED_BASES_FILE.chmod(0o600)


# ---------------------------------------------------------------------------
# Runtime DEK access
# ---------------------------------------------------------------------------


def is_encrypted_base(mem_id: str) -> bool:
    """Check if a memory base is configured for encryption."""
    bases = load_encrypted_bases()
    return mem_id in bases


def get_dek(mem_id: str, encrypted_dek_b64: str) -> bytes:
    """
    Get the DEK for a memory base, using cache or decrypting on demand.

    Flow: read password -> load encrypted_bases config -> decrypt private key
    -> decrypt DEK -> cache and return.
    """
    if mem_id in _dek_cache:
        return _dek_cache[mem_id]

    password = _read_password()
    if not password:
        raise RuntimeError(
            "Encryption password not found. "
            "Set DBAY_ENCRYPTION_PASSWORD or create ~/.dbay/secret"
        )

    bases = load_encrypted_bases()
    if mem_id not in bases:
        raise RuntimeError(f"No encryption config found for memory base '{mem_id}'")

    config = bases[mem_id]
    encrypted_private_key = config["encrypted_private_key"]
    salt = base64.b64decode(config["salt"])

    # Decrypt private key with password
    private_pem = decrypt_private_key(encrypted_private_key, password, salt)

    # Decrypt DEK with private key
    dek = decrypt_dek_with_private_key(encrypted_dek_b64, private_pem)

    _dek_cache[mem_id] = dek
    return dek
