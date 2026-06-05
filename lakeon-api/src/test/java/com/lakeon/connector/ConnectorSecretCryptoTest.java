package com.lakeon.connector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorSecretCryptoTest {
    @Test
    void encrypt_decrypt_roundTripDoesNotExposePlaintext() {
        ConnectorSecretCrypto crypto = new ConnectorSecretCrypto("0123456789abcdef0123456789abcdef");

        String encrypted = crypto.encrypt("{\"username\":\"postgres\",\"password\":\"secret\"}");
        String decrypted = crypto.decrypt(encrypted);

        assertThat(encrypted).doesNotContain("secret");
        assertThat(decrypted).isEqualTo("{\"username\":\"postgres\",\"password\":\"secret\"}");
    }
}
