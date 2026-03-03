package com.lakeon.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PostgreSQL SCRAM-SHA-256 password hash generation utility.
 *
 * Generates format: SCRAM-SHA-256$4096:<salt_base64>$<StoredKey_base64>:<ServerKey_base64>
 */
public class ScramUtils {
    private static final int ITERATIONS = 4096;

    public static String generateScramHash(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return generateScramHash(password, salt, ITERATIONS);
    }

    public static String generateScramHash(String password, byte[] salt, int iterations) {
        try {
            byte[] saltedPassword = hi(password.getBytes(StandardCharsets.UTF_8), salt, iterations);

            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(saltedPassword, "HmacSHA256"));
            byte[] clientKey = hmac.doFinal("Client Key".getBytes(StandardCharsets.UTF_8));

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] storedKey = md.digest(clientKey);

            hmac.init(new SecretKeySpec(saltedPassword, "HmacSHA256"));
            byte[] serverKey = hmac.doFinal("Server Key".getBytes(StandardCharsets.UTF_8));

            String saltBase64 = Base64.getEncoder().encodeToString(salt);
            String storedKeyBase64 = Base64.getEncoder().encodeToString(storedKey);
            String serverKeyBase64 = Base64.getEncoder().encodeToString(serverKey);

            return String.format("SCRAM-SHA-256$%d:%s$%s:%s",
                iterations, saltBase64, storedKeyBase64, serverKeyBase64);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate SCRAM hash", e);
        }
    }

    private static byte[] hi(byte[] password, byte[] salt, int iterations) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(password, "HmacSHA256"));

        byte[] u = new byte[salt.length + 4];
        System.arraycopy(salt, 0, u, 0, salt.length);
        u[u.length - 1] = 1;

        byte[] prev = hmac.doFinal(u);
        byte[] result = prev.clone();

        for (int i = 2; i <= iterations; i++) {
            hmac.init(new SecretKeySpec(password, "HmacSHA256"));
            byte[] current = hmac.doFinal(prev);
            for (int j = 0; j < result.length; j++) {
                result[j] ^= current[j];
            }
            prev = current;
        }
        return result;
    }
}
