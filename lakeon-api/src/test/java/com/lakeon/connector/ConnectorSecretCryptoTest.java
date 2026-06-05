package com.lakeon.connector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorSecretCryptoTest {
    private static final String TEST_KEY = "test-key-1234567890abcdef1234567";
    private static final String OLD_PUBLIC_DEFAULT_KEY = "0123456789abcdef0123456789abcdef";

    @Test
    void encrypt_decrypt_roundTripDoesNotExposePlaintext() {
        assertThat(TEST_KEY.getBytes()).hasSize(32);
        ConnectorSecretCrypto crypto = new ConnectorSecretCrypto(TEST_KEY);

        String encrypted = crypto.encrypt("{\"username\":\"postgres\",\"password\":\"secret\"}");
        String decrypted = crypto.decrypt(encrypted);

        assertThat(encrypted).doesNotContain("secret");
        assertThat(decrypted).isEqualTo("{\"username\":\"postgres\",\"password\":\"secret\"}");
    }

    @Test
    void constructor_rejectsMissingKey() {
        assertThatThrownBy(() -> new ConnectorSecretCrypto(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("required");
    }

    @Test
    void constructor_rejectsBlankKey() {
        assertThatThrownBy(() -> new ConnectorSecretCrypto("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("required");
    }

    @Test
    void constructor_rejectsOldPublicDefaultKey() {
        assertThatThrownBy(() -> new ConnectorSecretCrypto(OLD_PUBLIC_DEFAULT_KEY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("public default");
    }

    @Test
    void constructor_rejectsInvalidLengthKey() {
        assertThatThrownBy(() -> new ConnectorSecretCrypto("too-short"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("16, 24, or 32 bytes");
    }
}
