package com.lakeon.compute;

import com.lakeon.config.LakeonProperties;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Signs JWTs (RS256) used by lakeon-api to authenticate to compute_ctl's
 * HTTP API on individual compute pods. The matching public key is embedded
 * in each compute pod's spec via ComputeSpecBuilder so compute_ctl can verify.
 *
 * Private key is sourced from COMPUTE_JWT_PRIVATE_KEY env (PEM with
 * literal "\n" escapes preserved). When not configured (e.g., local dev),
 * signComputeCtlToken throws IllegalStateException; isConfigured() lets
 * callers gate.
 */
@Component
public class ComputeJwtSigner {
    private final LakeonProperties props;
    private final PrivateKey privateKey;

    public ComputeJwtSigner(LakeonProperties props) {
        this.props = props;
        String pem = props.getComputeJwt().getPrivateKey();
        if (pem == null || pem.isBlank()) {
            this.privateKey = null;
            return;
        }
        try {
            // Handle both real newlines (from helm Secret) and literal "\n" (from raw env)
            String normalized = pem.replace("\\n", "\n");
            String body = normalized.replaceAll("-----[^-]+-----", "")
                                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            this.privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to load COMPUTE_JWT_PRIVATE_KEY: " + e.getMessage(), e);
        }
    }

    /**
     * Generate a JWT for compute_ctl HTTP API authentication.
     * Subject is the database / compute id. The "compute_id" custom claim
     * is required by upstream compute_ctl's authorize middleware (it matches
     * against the pod's own --compute-id arg). Issuer/TTL come from config.
     */
    public String signComputeCtlToken(String computeId) {
        if (privateKey == null) {
            throw new IllegalStateException(
                "ComputeJwtSigner not configured (COMPUTE_JWT_PRIVATE_KEY missing)");
        }
        Instant now = Instant.now();
        return Jwts.builder()
            .header().keyId(props.getComputeJwt().getKid()).and()
            .subject(computeId)
            .claim("compute_id", computeId)
            .issuer(props.getComputeJwt().getIssuer())
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(props.getComputeJwt().getTtlSeconds())))
            .signWith(privateKey, Jwts.SIG.RS256)
            .compact();
    }

    public boolean isConfigured() {
        return privateKey != null;
    }
}
