"""E2E tests for encrypted memory bases.

Tests the full encryption pipeline:
  create encrypted base -> ingest (encrypt) -> recall (decrypt) -> list (decrypt) -> delete
"""
import base64
import json
import os
import sys
import time

import pytest

# Allow importing dbay_mcp without pip install -e
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "dbay-mcp", "src"))

from dbay_mcp.crypto import (
    generate_keypair, generate_dek, generate_salt,
    encrypt_private_key, encrypt_dek_with_public_key,
    decrypt_private_key, decrypt_dek_with_private_key,
    encrypt_content, decrypt_content,
    save_encrypted_base, write_secret, load_encrypted_bases,
    ENCRYPTED_BASES_FILE,
)


TEST_PASSWORD = "e2e-test-password-2026"


def _embedding(seed: float = 0.01) -> list[float]:
    return [seed] * 1024


# ---------------------------------------------------------------------------
# Module-scoped fixtures for server integration tests
# ---------------------------------------------------------------------------


@pytest.fixture(scope="module")
def e2e_client(e2e_tenant):
    return e2e_tenant["client"]


@pytest.fixture(scope="module")
def encrypted_base(e2e_client):
    """Create an encrypted memory base with full key setup."""
    # Generate keys
    private_pem, public_pem = generate_keypair()
    salt = generate_salt()
    dek = generate_dek()

    encrypted_private_key = encrypt_private_key(private_pem, TEST_PASSWORD, salt)
    encrypted_dek = encrypt_dek_with_public_key(dek, public_pem)

    # Create on server
    base = e2e_client.create_memory_base(
        name=f"e2e-encrypted-{int(time.time())}",
        encrypted=True,
        encrypted_dek=encrypted_dek,
        kdf_salt=base64.b64encode(salt).decode("ascii"),
        embedding_dim=1024,
    )

    # Wait for READY
    for _ in range(60):
        info = e2e_client.get_memory_base(base["id"])
        if info["status"] == "READY":
            break
        time.sleep(2)
    assert info["status"] == "READY"
    assert info["encrypted"] is True

    # Save local config
    config = {
        "public_key": public_pem.decode("ascii"),
        "encrypted_private_key": encrypted_private_key,
        "kdf_salt": base64.b64encode(salt).decode("ascii"),
        "kdf_algorithm": "scrypt",
        "embedding_provider": "dbay",
        "embedding_dim": 1024,
    }
    save_encrypted_base(info["id"], config)
    write_secret(TEST_PASSWORD)

    yield {
        "info": info,
        "dek": dek,
        "private_pem": private_pem,
        "public_pem": public_pem,
    }

    # Cleanup
    try:
        e2e_client.delete_memory_base(base["id"])
    except Exception:
        pass
    # Clean up local config
    bases = load_encrypted_bases()
    bases.pop(info["id"], None)
    ENCRYPTED_BASES_FILE.write_text(json.dumps(bases, indent=2) + "\n")


# ---------------------------------------------------------------------------
# Class 1: Crypto unit tests (no server needed)
# ---------------------------------------------------------------------------


class TestCryptoUnit:
    """Unit-level crypto tests (no server needed)."""

    def test_keypair_generation(self):
        """RSA-4096 key pair has correct PEM headers."""
        private_pem, public_pem = generate_keypair()
        assert b"BEGIN PRIVATE KEY" in private_pem
        assert b"BEGIN PUBLIC KEY" in public_pem

    def test_dek_generation(self):
        """DEK is 256-bit (32 bytes)."""
        dek = generate_dek()
        assert len(dek) == 32

    def test_private_key_encrypt_decrypt(self):
        """Round-trip encrypt/decrypt of private key with password."""
        private_pem, _ = generate_keypair()
        salt = generate_salt()
        encrypted = encrypt_private_key(private_pem, "test-pwd", salt)
        decrypted = decrypt_private_key(encrypted, "test-pwd", salt)
        assert decrypted == private_pem

    def test_private_key_wrong_password(self):
        """Wrong password raises exception."""
        private_pem, _ = generate_keypair()
        salt = generate_salt()
        encrypted = encrypt_private_key(private_pem, "correct", salt)
        with pytest.raises(Exception):
            decrypt_private_key(encrypted, "wrong", salt)

    def test_dek_rsa_encrypt_decrypt(self):
        """Round-trip RSA encrypt/decrypt of DEK."""
        private_pem, public_pem = generate_keypair()
        dek = generate_dek()
        encrypted_dek = encrypt_dek_with_public_key(dek, public_pem)
        decrypted_dek = decrypt_dek_with_private_key(encrypted_dek, private_pem)
        assert decrypted_dek == dek

    def test_content_encrypt_decrypt(self):
        """Round-trip content encryption."""
        dek = generate_dek()
        plaintext = "My API key is sk-secret123"
        encrypted = encrypt_content(dek, plaintext)
        assert encrypted != plaintext
        decrypted = decrypt_content(dek, encrypted)
        assert decrypted == plaintext

    def test_content_encrypt_different_nonce(self):
        """Same plaintext produces different ciphertext (random nonce)."""
        dek = generate_dek()
        plaintext = "same content"
        a = encrypt_content(dek, plaintext)
        b = encrypt_content(dek, plaintext)
        assert a != b  # Different nonces
        assert decrypt_content(dek, a) == decrypt_content(dek, b) == plaintext

    def test_full_three_factor_chain(self):
        """End-to-end: password -> private_key -> DEK -> content."""
        password = "my-password"
        private_pem, public_pem = generate_keypair()
        salt = generate_salt()
        dek = generate_dek()

        # Encrypt chain
        encrypted_private_key = encrypt_private_key(private_pem, password, salt)
        encrypted_dek = encrypt_dek_with_public_key(dek, public_pem)
        encrypted_content = encrypt_content(dek, "secret data")

        # Decrypt chain (simulating MCP runtime)
        recovered_private_pem = decrypt_private_key(encrypted_private_key, password, salt)
        recovered_dek = decrypt_dek_with_private_key(encrypted_dek, recovered_private_pem)
        recovered_content = decrypt_content(recovered_dek, encrypted_content)

        assert recovered_content == "secret data"


# ---------------------------------------------------------------------------
# Class 2: Server integration tests
# ---------------------------------------------------------------------------


class TestEncryptedMemoryBase:
    """Server integration tests for encrypted memory bases."""

    def test_base_is_encrypted(self, encrypted_base, e2e_client):
        """Verify the created base has encryption fields."""
        info = e2e_client.get_memory_base(encrypted_base["info"]["id"])
        assert info["encrypted"] is True
        assert info["encrypted_dek"] is not None
        assert info["embedding_dim"] == 1024

    def test_ingest_encrypted(self, encrypted_base, e2e_client):
        """Ingest with client-side encryption: server stores ciphertext."""
        mem_id = encrypted_base["info"]["id"]
        dek = encrypted_base["dek"]
        plaintext = "My server IP is 10.0.1.5"

        # Encrypt content
        encrypted_content = encrypt_content(dek, plaintext)
        assert encrypted_content != plaintext

        # Ingest encrypted content
        result = e2e_client.mem_ingest(
            mem_id, content=encrypted_content,
            signal="memory", memory_type="fact",
            importance=0.8,
            embedding=_embedding(),
        )
        assert result["status"] == "stored"

        # Verify server has ciphertext, not plaintext
        memories = e2e_client.mem_list(mem_id)
        stored = next(m for m in memories["memories"] if m["id"] == result["memory_id"])
        assert stored["content"] != plaintext
        assert stored["content"] == encrypted_content

        # Client can decrypt
        decrypted = decrypt_content(dek, stored["content"])
        assert decrypted == plaintext

    def test_ingest_and_recall_via_mcp(self, encrypted_base, e2e_client):
        """Full MCP flow: ingest -> recall with transparent encryption."""
        mem_id = encrypted_base["info"]["id"]
        dek = encrypted_base["dek"]
        plaintext = "Project deadline is 2026-05-01"

        encrypted_content = encrypt_content(dek, plaintext)

        result = e2e_client.mem_ingest(
            mem_id, content=encrypted_content,
            signal="memory", memory_type="fact",
            embedding=_embedding(0.02),
        )
        assert result["status"] == "stored"

        # Recall - server returns ciphertext, we decrypt
        time.sleep(1)
        recall_result = e2e_client.mem_recall(
            mem_id,
            query="project deadline",
            query_embedding=_embedding(0.02),
        )
        found = False
        for m in recall_result.get("memories", []):
            try:
                decrypted = decrypt_content(dek, m["content"])
                if "2026-05-01" in decrypted:
                    found = True
                    break
            except Exception:
                continue
        assert found, "Could not find and decrypt the ingested memory"

    def test_delete_encrypted_memory(self, encrypted_base, e2e_client):
        """Delete works the same for encrypted memories."""
        mem_id = encrypted_base["info"]["id"]
        dek = encrypted_base["dek"]

        encrypted_content = encrypt_content(dek, "to be deleted")
        result = e2e_client.mem_ingest(
            mem_id, content=encrypted_content,
            signal="memory", memory_type="fact",
            embedding=_embedding(0.03),
        )
        memory_id = result["memory_id"]

        e2e_client.mem_delete(mem_id, memory_id)

        memories = e2e_client.mem_list(mem_id)
        assert not any(m["id"] == memory_id for m in memories["memories"])

    def test_multi_tenant_isolation_encrypted(self, encrypted_base, e2e_client):
        """Other tenants cannot access encrypted memory base."""
        from conftest import _create_tenant_with_invite, ENDPOINT, ADMIN_TOKEN
        from dbay_cli.client import DbayClient, DbayApiError

        ts = int(time.time())
        client_b, tenant_b = _create_tenant_with_invite(
            ENDPOINT, ADMIN_TOKEN,
            f"e2e-enc-iso-{ts}", f"E2eTest@{ts}", f"Tenant Enc {ts}"
        )
        try:
            with pytest.raises(DbayApiError) as exc:
                client_b.get_memory_base(encrypted_base["info"]["id"])
            assert exc.value.status_code == 404
        finally:
            admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
            try:
                admin.admin_batch_delete_tenants([tenant_b["id"]])
            except Exception:
                pass
