package com.lakeon.compute;

import com.lakeon.config.LakeonProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComputeJwtSignerTest {
    // Generated 2026-05-16 for tests only — no production value, never deployed.
    private static final String TEST_PRIVATE_PEM = """
        -----BEGIN PRIVATE KEY-----
        MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDN0p9GrsdxuaIU
        72R56Wr7VLpRcFvbmEW613V7DnAuazIkDY8g5/GV+aLI3yhcNOoSVRS/WjZfb5up
        e0oFF6dmk54LDbvu47HuqZmAOHVaBna2QZai2AGwbv0sGkA5uaIzO1u8BrGCb//f
        Y2yA56kgBo62w8GyjM0LT84lIZ/2EW0g0GN4lG1BwnvVwI7OFaDj65r8xpgNs9Fq
        1pj9+SReghNjXDN/McR9PiUEwaoNdkAX3gF8GnwP3BOqrsmpdnNceCai+orGg5Fq
        wbZRDddZC+So+lPE3TjWbtEOqN0Dy+MyB9jtl5y+2r8gnrT1c3fpJidfc3r1nsim
        GYoReqJlAgMBAAECggEAKt6RBr2eNIGD8STYkmzr97vSz+Ydd9xcP7mjUlV0R7LT
        n39EfcfZwZFclsamRyhNTbKzbtS5KQEb1L3lcbCW0S5zd11ttKjv1WQ1YOfBh5X7
        kWQRXksr9OX1LQOtt2hDELUvGvdw7xhpXMu+XI4D47QB8y7MUCZ0CcReTU0W3n8s
        aMkW8lyyztFoFRwlgcdgyrQ5uDLKjD76KAWS7VqA8PsSQgWz4A/YRrjeonfjkhox
        0WS6isPy3nqcUD/752Y9J+C6tN3XLBHk1TXto3yruB6P7Z7u+2YC2OMAlF9vkgaX
        X82PCHZcIi4nNPOED7fhjGvUVjMiEguUEJUGiQo2UQKBgQDzYWxc9aWcs7jq5A7d
        WUpWWMRu/VQD/ECLRxVNQKAZdcFCdtL6//87d3j270Xf8wmumduiyepyaAc3bwlA
        KVcjRQeQfLDpxwWr2ypDhxZ3XFnac6PiJOPAOsDC3nu5eUx+2ahTaYZqumxAZYLd
        Ni4rBWRCZcMz66o64S2oKEWblQKBgQDYfqpJ29uooaZ4fjOEZow4KH78PdoieRq/
        FycP3jwTa1HnXcjB3KtIYU/FlWt4+PnC1nB1XroOlt0tT2dAXHQqVRiz3SUstGYJ
        2TAMvBKvxrcrE48nnBoUg5Vihg06xNMwEdvESTHn2Sad6s+IEyQGfDx+0z4zJYyi
        bvIUGv63kQKBgB0IgC7OtwTsg6Cxt/w7zJxkVnqPCdi33NNAlY/zp6Wh4H4XQq/i
        ngXwCKQcgw9mJL+JZyQSRj+DnWjFfCsFQ3nXoEFiPpCEx25q5K3NjaaLg8SFiwVN
        NUYXPCuC8ut7Rt7TBDt/GSPePU+pTGUrM3K6X+1zykeFU3dWqWWn+DXJAoGARzl8
        2qm7XYI5G2Ehn8iBDyS7ik7rCfZfx0hdsInDp/vhyUWAe88WhsyFCxL6daUrvl8A
        Rozwl0Yo4/RAmtsP2LGAXARAa7G59DmA5l+lojC1KDXaHgTsS51ysyQ5DAGfHSxy
        6ePOyGEXpFKRDkqFyqBq4qqqxvbuiq4HdHfhHxECgYEAr2FmgDRl8yf5gDsIkLIE
        ccUHULYgZMYVepx9Uxciiqr1qJ9jtekC1dWbNGO5FcT2tuW4Wu87Mu6FR5yzrq5o
        zNSk0cWgbmTirhizNpt5c6+hnG0ZhIjtdBJqxtshBBemvYcJvWjfgjIuxpyPS/yK
        Kp5CRTey17WLqCWAO9zQP6Y=
        -----END PRIVATE KEY-----
        """;
    private static final String TEST_PUBLIC_PEM = """
        -----BEGIN PUBLIC KEY-----
        MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAzdKfRq7HcbmiFO9keelq
        +1S6UXBb25hFutd1ew5wLmsyJA2PIOfxlfmiyN8oXDTqElUUv1o2X2+bqXtKBRen
        ZpOeCw277uOx7qmZgDh1WgZ2tkGWotgBsG79LBpAObmiMztbvAaxgm//32NsgOep
        IAaOtsPBsozNC0/OJSGf9hFtINBjeJRtQcJ71cCOzhWg4+ua/MaYDbPRataY/fkk
        XoITY1wzfzHEfT4lBMGqDXZAF94BfBp8D9wTqq7JqXZzXHgmovqKxoORasG2UQ3X
        WQvkqPpTxN041m7RDqjdA8vjMgfY7Zecvtq/IJ609XN36SYnX3N69Z7IphmKEXqi
        ZQIDAQAB
        -----END PUBLIC KEY-----
        """;

    private LakeonProperties props;
    private ComputeJwtSigner signer;

    @BeforeEach
    void setup() {
        props = new LakeonProperties();
        props.getComputeJwt().setPrivateKey(TEST_PRIVATE_PEM);
        props.getComputeJwt().setKid("test-key-1");
        signer = new ComputeJwtSigner(props);
    }

    @Test
    void signComputeCtlToken_producesValidJws_verifiableWithPublicKey() throws Exception {
        String token = signer.signComputeCtlToken("db_abc");

        byte[] pubBytes = Base64.getMimeDecoder().decode(
            TEST_PUBLIC_PEM.replaceAll("-----[^-]+-----", "").trim());
        RSAPublicKey pub = (RSAPublicKey) KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(pubBytes));
        var claims = Jwts.parser().verifyWith(pub).build()
            .parseSignedClaims(token).getPayload();
        assertThat(claims.getSubject()).isEqualTo("db_abc");
        assertThat(claims.getIssuer()).isEqualTo("lakeon-api");
    }

    @Test
    void signComputeCtlToken_includesKidHeader() throws Exception {
        String token = signer.signComputeCtlToken("db_abc");
        String headerJson = new String(Decoders.BASE64URL.decode(token.split("\\.")[0]));
        assertThat(headerJson).contains("\"kid\":\"test-key-1\"");
    }

    @Test
    void signComputeCtlToken_throwsWhenNotConfigured() {
        LakeonProperties empty = new LakeonProperties();
        ComputeJwtSigner s = new ComputeJwtSigner(empty);
        assertThatThrownBy(() -> s.signComputeCtlToken("db_x"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void isConfigured_falseWhenPrivateKeyEmpty() {
        LakeonProperties empty = new LakeonProperties();
        ComputeJwtSigner s = new ComputeJwtSigner(empty);
        assertThat(s.isConfigured()).isFalse();
    }

    @Test
    void constructor_throwsOnMalformedPem() {
        LakeonProperties bad = new LakeonProperties();
        bad.getComputeJwt().setPrivateKey("not-a-pem-just-garbage");
        assertThatThrownBy(() -> new ComputeJwtSigner(bad))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("COMPUTE_JWT_PRIVATE_KEY");
    }
}
