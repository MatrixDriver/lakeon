# Compute Warm Pool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut dbay.cloud database cold start from ~2s to <1s by maintaining a pool of pre-started compute pods and switching tenants via Neon's `compute_ctl /configure` HTTP API instead of creating a fresh Pod every time.

**Architecture:** A new `ComputeWarmPoolManager` keeps N idle compute pods running with placeholder specs. On cold start, `ComputeLifecycleService.doWakeCompute` claims an idle pod and POSTs the real tenant spec to its `compute_ctl` HTTP endpoint, which performs basebackup + reconfigure (~400-500ms) instead of waiting for a fresh container (~640ms of OS+CNI work). Falls back to the existing `createComputePod` path on pool miss or claim failure. Idle pods are deleted after claim (never reused across tenants) to preserve the per-pod safety guarantees we have today.

**Tech Stack:** Spring Boot 3.3.5, Java 17, fabric8 KubernetesClient, JJWT for signing, Neon `compute_ctl` v17 (upstream).

---

## Background: Why this is worth doing

Production data from 2026-05-15 / 16 (`docs/...` and DBay memory id=103):

```
Cold-start total = 1981ms (after our podCreate fix from 2147ms)
  ├── podCreate         247ms    (K8s API + IP wait — already optimized)
  ├── containersReady  1024ms    (640ms container/CNI + 382ms compute_ctl init)
  └── other              ~50ms
```

`compute_ctl` ComputeMetrics from production log (db_c7acd26f):
- `wait_for_spec_ms: 0`, `sync_safekeepers: 4ms`, `pageserver_connect: 6.5ms`
- `basebackup: 37ms` (82KB DB), `start_postgres: 24ms`
- `config_ms: 314ms` (apply SQL spec — catalog, roles, schema, migrations)
- `total_startup_ms: 382ms`

**The 640ms inside containersReady that is NOT compute_ctl** = container runtime + image mount + cgroup/namespace + CNI/IP setup. That's the only part warm pool can shortcut. compute_ctl's own 382ms (and especially `config_ms: 314ms`) **must run again** at reconfigure time and cannot be avoided.

**Predicted warm-claim cold start: ~500-600ms** (no container setup + Java/HTTP overhead 100ms + compute_ctl reconfigure 400ms). Above target for small/medium DBs; **degrades to 1-3s for GB-scale DBs** because basebackup_ms scales with data size.

## Decisions (resolved 2026-05-16)

All five original decision points + 2 implicit ones surfaced during B1 are now resolved. Reasoning preserved below for future reference.

| # | Decision | Rationale |
|---|---|---|
| 1 | JWT algorithm: **RS256** (locked in B1) | Private key never leaves lakeon-api; public JWK in spec matches Neon upstream design |
| 2 | Pool namespace: **reuse `lakeon-compute`** + `lakeon.io/pool=warm` label | No new RBAC/quota; compute pods stay grouped for SRE; existing `lakeon_compute_pods_active` gauge gets a label-selector exclusion for pool pods |
| 3 | Multi-image pool: **single pool, dominant image** + `lakeon_warm_pool_miss_by_image` counter | 4× pools is 4× resource cost; revisit after B5 week-1 data |
| 4 | Dummy spec: **mock tenant `warm-pool-tenant`** | compute_ctl requires `--config` at startup, no "wait for spec" mode; one shared mock tenant on pageserver is cheap (~KB data) |
| 5 | Pool-miss: **fallback to `createComputePod`** | Zero behavioral regression; queueing adds tail-latency risk |
| 6 | Claim policy: **claim-once-then-delete** | Pod consumed by one tenant then destroyed; reconcile replenishes. Preserves today's per-pod isolation; avoids audit of "what gets left behind" |
| 7 | Reconfigure timeout: **1.5s ceiling**, then fallback to `createComputePod` | Cold-init measured 382ms; 1.5s = 4× headroom for GB-scale basebackup |

## Original Decision Reasoning (for context)

1. **JWT signing algorithm**
   - `RS256` (RSA, asymmetric): public key in spec, private key in secret. More moving parts, but private key never leaves lakeon-api.
   - `HS256` (HMAC, symmetric): shared secret embedded in spec as both signing key and verification key. Simpler; risk = shared secret leaks into Pod environment.
   - **Recommendation:** RS256. compute_ctl's JWKS is designed for it; HS256 with shared secret defeats the auth model.

2. **Pool namespace**
   - Reuse `lakeon-compute` (where real compute pods already live).
   - New dedicated `lakeon-compute-pool` namespace.
   - **Recommendation:** Reuse `lakeon-compute` with label selector (`lakeon.io/pool=warm`). Saves resource quota juggling and matches existing `WarmPoolManager` (notebook) pattern.

3. **Multi-image pool strategy**
   - There are 4 compute images today: `compute-pgsearch`, `compute-pgsearch-neon`, `compute-pgsearch-ctl`, `compute-zhparser`. Each is a distinct binary set.
   - Options:
     - **A.** One pool per image (4 pools, 4× resource cost).
     - **B.** Single pool with the most-common image; fall back to `createComputePod` for other images.
     - **C.** Single pool with a "superset" image containing all extensions.
   - **Recommendation:** Start with **B** using the dominant image (whichever shows up most in `wake breakdown` logs). Confirm via metrics. Revisit after rollout.

4. **Dummy spec construction**
   - Compute_ctl requires a valid `spec` to start. Options:
     - **A.** Mock tenant: create a real but unused Neon tenant whose only purpose is to hold dummy state. Pool pods attach to this tenant.
     - **B.** "No tenant" mode: leave spec fields empty/null and rely on compute_ctl entering an `Empty` state. **Risk:** upstream may reject; we'd need to confirm by reading compute_ctl source.
     - **C.** First-claim init: pod starts in `Init` state without spec, waits for first `/configure`. **Risk:** compute_ctl must support this; openapi spec implies `wait_for_spec` is a real state but unclear if reachable from CLI.
   - **Recommendation:** **A** (mock tenant). Predictable, no upstream archaeology. Costs one extra tenant on pageserver.

5. **Pool-miss behavior**
   - Pool empty when claim arrives. Options:
     - **A.** Fall back to existing `createComputePod` (no behavioral regression, but loses the latency win on miss).
     - **B.** Queue caller, wait for next pod to come up.
     - **C.** Fail fast.
   - **Recommendation:** **A**. Pool size is a knob; misses should be rare in steady state. Queuing adds tail-latency risk.

After deciding, update this doc's "Decisions" section and proceed to B1.

---

## File Structure

```
lakeon-api/src/main/java/com/lakeon/
├── compute/                                    # NEW package
│   ├── ComputeWarmPoolManager.java             # B2: pool maintenance + claim/release
│   ├── ComputeReconfigureClient.java           # B1+B2: HTTP client for compute_ctl /configure
│   └── ComputeJwtSigner.java                   # B1: signs JWT for compute_ctl auth
├── config/
│   └── LakeonProperties.java                   # B1+B2: add WarmPoolConfig section
├── k8s/
│   ├── ComputeSpecBuilder.java                 # B1: inject JWKS public key into spec.compute_ctl_config
│   └── ComputePodManager.java                  # B2: createIdleComputePod() helper, labels for pool
└── service/
    └── ComputeLifecycleService.java            # B3: try warm-claim before createComputePod

lakeon-api/src/main/resources/
└── application.yaml                            # B1+B2: warm pool config defaults

lakeon-api/src/test/java/com/lakeon/
├── compute/
│   ├── ComputeJwtSignerTest.java               # B1: verify JWT structure + signature
│   ├── ComputeReconfigureClientTest.java       # B1+B2: mock HTTP response
│   └── ComputeWarmPoolManagerTest.java         # B2: pool reconcile + claim logic
└── service/
    └── ComputeLifecycleServiceWarmPoolTest.java # B3: integration of claim path

deploy/helm/lakeon/
├── templates/secret-compute-jwt.yaml           # B1: RSA private key Secret
└── values.yaml                                 # B1+B2: warm pool size + enabled flag

deploy/cce/sites/hwstaff/
└── .env                                        # B1: COMPUTE_JWT_PRIVATE_KEY (PEM) injected at deploy
```

**Why this split:**
- New `compute` package isolates warm pool concerns from the existing `notebook.WarmPoolManager` (Ray-specific). Same conceptual shape, different domain.
- `ComputeReconfigureClient` is reused in B2 (claim path) and B3 (probably nowhere else, but makes B2 unit-testable).
- `ComputeSpecBuilder` change is isolated to a single spot (the `compute_ctl_config.jwks` field) so it doesn't bleed into other code.

---

## Phase B1: JWT Auth Infrastructure

**Goal:** Make `compute_ctl`'s `/configure` HTTP endpoint reachable from `lakeon-api`. Today every request returns `{"error":"invalid authorization token"}` because `compute_ctl_config.jwks.keys` is empty. This phase: generate an RS256 keypair, inject the public JWK into every compute spec, write a Java signer for the private key.

**Why first:** Every other phase needs to be able to call `/configure`. B2's reconcile loop, B3's claim path, B4's E2E test — all depend on this working. Without it, warm pool is a non-starter.

**Estimate:** 3-5 days (1-2 of code + 1-2 of operational rollout/key management).

### Task B1.1: Generate the keypair offline and add to env

**Files:**
- Create: `deploy/cce/sites/hwstaff/.env.example` (template) — DO NOT commit the real key
- Modify: `deploy/cce/sites/hwstaff/.env` (gitignored, locally only)

- [ ] **Step 1: Generate RSA-2048 keypair**

```bash
openssl genrsa -out /tmp/compute-jwt-private.pem 2048
openssl rsa -in /tmp/compute-jwt-private.pem -pubout -out /tmp/compute-jwt-public.pem
```

- [ ] **Step 2: Compute the JWK form of the public key**

We'll need the JSON Web Key representation to embed in `compute_ctl_config.jwks.keys`. Use a one-shot Python helper:

```bash
python3 -c "
import json, base64
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicNumbers
with open('/tmp/compute-jwt-public.pem','rb') as f:
    pub = load_pem_public_key(f.read())
nums = pub.public_numbers()
def b64u(i): return base64.urlsafe_b64encode(i.to_bytes((i.bit_length()+7)//8,'big')).rstrip(b'=').decode()
print(json.dumps({'kty':'RSA','use':'sig','alg':'RS256','kid':'lakeon-compute-1','n':b64u(nums.n),'e':b64u(nums.e)}))
" > /tmp/compute-jwt-public.jwk.json
cat /tmp/compute-jwt-public.jwk.json
```

Expected output: a single-line JSON with `kty`, `n`, `e`, `kid` fields.

- [ ] **Step 3: Inject into `.env` (LOCAL, not committed)**

Add to `deploy/cce/sites/hwstaff/.env`:

```bash
# Inline the PEM as a single-line value (newlines preserved with \n)
COMPUTE_JWT_PRIVATE_KEY="$(cat /tmp/compute-jwt-private.pem | awk '{printf "%s\\n", $0}')"
COMPUTE_JWT_PUBLIC_JWK='<paste the JSON from step 2 here>'
COMPUTE_JWT_KID=lakeon-compute-1
```

- [ ] **Step 4: Wipe local key files**

```bash
shred -u /tmp/compute-jwt-private.pem /tmp/compute-jwt-public.pem /tmp/compute-jwt-public.jwk.json
```

- [ ] **Step 5: Update local `.env.example` (NOT committed)**

Repo convention (`.gitignore`): `.env.*` is fully ignored — including `.env.example`. Templates live locally for reference only. Append to `deploy/cce/sites/hwstaff/.env.example`:

```bash
# Compute warm pool JWT keys (Phase B1)
# Generate locally with: openssl genrsa -out priv.pem 2048 && openssl rsa -in priv.pem -pubout -out pub.pem
# Convert priv PEM to inline: awk '{printf "%s\\n", $0}' priv.pem
# Build JWK from pub PEM with the python helper in Step 2 above.
# Never commit real values — this file is template only and stays gitignored.
COMPUTE_JWT_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\n...PEM with \\n-escaped newlines...\n-----END PRIVATE KEY-----\n"
COMPUTE_JWT_PUBLIC_JWK='{"kty":"RSA","use":"sig","alg":"RS256","kid":"lakeon-compute-1","n":"...","e":"AQAB"}'
COMPUTE_JWT_KID=lakeon-compute-1
```

- [ ] **Step 6: No commit for B1.1**

`.env` and `.env.example` are both gitignored. The visible commits for B1 start in B1.2 (LakeonProperties + helm Secret template).

### Task B1.2: Wire env into lakeon-api config

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`
- Modify: `lakeon-api/src/main/resources/application.yaml`
- Modify: `deploy/helm/lakeon/values.yaml` and `deploy/helm/lakeon/templates/api-deployment.yaml` (env injection)

- [ ] **Step 1: Add ComputeJwtConfig nested class to LakeonProperties**

Add inside `LakeonProperties`:

```java
@Data
public static class ComputeJwtConfig {
    /** PEM-encoded RSA private key. Newlines should be \n-escaped in env. */
    private String privateKey;
    /** JWK JSON of the public key (single line). */
    private String publicJwk;
    /** Key ID matching what's in publicJwk. */
    private String kid = "lakeon-compute-1";
    /** Optional issuer claim. */
    private String issuer = "lakeon-api";
    /** Token TTL in seconds for /configure calls. */
    private int ttlSeconds = 300;
}

private ComputeJwtConfig computeJwt = new ComputeJwtConfig();
```

- [ ] **Step 2: Bind env to it in `application.yaml`**

```yaml
lakeon:
  compute-jwt:
    private-key: ${COMPUTE_JWT_PRIVATE_KEY:}
    public-jwk: ${COMPUTE_JWT_PUBLIC_JWK:}
    kid: ${COMPUTE_JWT_KID:lakeon-compute-1}
```

- [ ] **Step 3: Pass env through helm**

In `deploy/helm/lakeon/templates/api-deployment.yaml`, add to the api container's `env:`:

```yaml
- name: COMPUTE_JWT_PRIVATE_KEY
  valueFrom: { secretKeyRef: { name: lakeon-compute-jwt, key: private-key } }
- name: COMPUTE_JWT_PUBLIC_JWK
  valueFrom: { secretKeyRef: { name: lakeon-compute-jwt, key: public-jwk } }
- name: COMPUTE_JWT_KID
  valueFrom: { secretKeyRef: { name: lakeon-compute-jwt, key: kid } }
```

Create `deploy/helm/lakeon/templates/secret-compute-jwt.yaml`:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: lakeon-compute-jwt
  namespace: {{ .Release.Namespace }}
type: Opaque
stringData:
  private-key: {{ .Values.computeJwt.privateKey | quote }}
  public-jwk: {{ .Values.computeJwt.publicJwk | quote }}
  kid: {{ .Values.computeJwt.kid | quote }}
```

Update `deploy/helm/lakeon/values.yaml`:

```yaml
computeJwt:
  privateKey: ""
  publicJwk: ""
  kid: "lakeon-compute-1"
```

- [ ] **Step 4: Wire `deploy/cce/deploy.sh` to pass the secrets**

In `deploy/cce/deploy.sh` near the existing `helm upgrade ... --set api.logDbDsn="$LOG_DB_DSN"` line, add:

```bash
  --set computeJwt.privateKey="$COMPUTE_JWT_PRIVATE_KEY" \
  --set computeJwt.publicJwk="$COMPUTE_JWT_PUBLIC_JWK" \
  --set computeJwt.kid="$COMPUTE_JWT_KID" \
```

And add `COMPUTE_JWT_PRIVATE_KEY`, `COMPUTE_JWT_PUBLIC_JWK` to the env-presence check loop at the top.

- [ ] **Step 5: Build + verify env reaches the pod (no helm yet)**

```bash
mvn -DskipTests compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java \
       lakeon-api/src/main/resources/application.yaml \
       deploy/helm/lakeon/values.yaml \
       deploy/helm/lakeon/templates/secret-compute-jwt.yaml \
       deploy/helm/lakeon/templates/api-deployment.yaml \
       deploy/cce/deploy.sh
git commit -m "chore(api): wire COMPUTE_JWT_* env into LakeonProperties + helm Secret"
```

### Task B1.3: Inject JWKS into `ComputeSpecBuilder`

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/k8s/ComputeSpecBuilder.java:62` (the line currently `Map.of("jwks", Map.of("keys", List.of()))`)
- Modify: `lakeon-api/src/main/java/com/lakeon/k8s/ComputePodManager.java:35-46` (constructor signature gains props if not already there)

- [ ] **Step 1: Write the failing test**

Create `lakeon-api/src/test/java/com/lakeon/k8s/ComputeSpecBuilderJwksTest.java`:

```java
package com.lakeon.k8s;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ComputeSpecBuilderJwksTest {
    @Test
    void generateComputeConfig_includesJwksKey_whenPublicJwkConfigured() throws Exception {
        LakeonProperties props = new LakeonProperties();
        props.getComputeJwt().setPublicJwk(
            "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"k1\",\"n\":\"AA\",\"e\":\"AQAB\"}");
        ObjectMapper om = new ObjectMapper();
        ComputeSpecBuilder builder = new ComputeSpecBuilder(props, om);

        DatabaseEntity e = new DatabaseEntity();
        e.setId("db_test"); e.setName("test"); e.setNeonTenantId("t"); e.setNeonTimelineId("tl");

        String json = builder.generateComputeConfig(e, 0);
        JsonNode root = om.readTree(json);
        JsonNode keys = root.path("compute_ctl_config").path("jwks").path("keys");
        assertThat(keys.isArray()).isTrue();
        assertThat(keys.size()).isEqualTo(1);
        assertThat(keys.get(0).get("kid").asText()).isEqualTo("k1");
    }

    @Test
    void generateComputeConfig_emptyJwks_whenNoPublicKeyConfigured() throws Exception {
        LakeonProperties props = new LakeonProperties();
        // publicJwk left null/empty
        ObjectMapper om = new ObjectMapper();
        ComputeSpecBuilder builder = new ComputeSpecBuilder(props, om);
        DatabaseEntity e = new DatabaseEntity();
        e.setId("db_test"); e.setName("test"); e.setNeonTenantId("t"); e.setNeonTimelineId("tl");

        String json = builder.generateComputeConfig(e, 0);
        JsonNode keys = om.readTree(json).path("compute_ctl_config").path("jwks").path("keys");
        assertThat(keys.isArray()).isTrue();
        assertThat(keys.size()).isEqualTo(0);
    }
}
```

- [ ] **Step 2: Run test (expect FAIL — current builder hardcodes empty keys list)**

```bash
mvn -Dtest=ComputeSpecBuilderJwksTest test
```
Expected: FAIL on the first test (keys.size() == 0).

- [ ] **Step 3: Modify ComputeSpecBuilder.java line 62**

Replace:

```java
Map<String, Object> config = Map.of(
    "spec", spec,
    "compute_ctl_config", Map.of("jwks", Map.of("keys", List.of()))
);
```

With:

```java
List<Map<String, Object>> jwksKeys = new ArrayList<>();
String publicJwk = props.getComputeJwt().getPublicJwk();
if (publicJwk != null && !publicJwk.isBlank()) {
    try {
        Map<String, Object> jwk = objectMapper.readValue(publicJwk, Map.class);
        jwksKeys.add(jwk);
    } catch (Exception ex) {
        throw new RuntimeException("Invalid COMPUTE_JWT_PUBLIC_JWK: " + ex.getMessage(), ex);
    }
}
Map<String, Object> config = Map.of(
    "spec", spec,
    "compute_ctl_config", Map.of("jwks", Map.of("keys", jwksKeys))
);
```

- [ ] **Step 4: Run test again (expect PASS)**

```bash
mvn -Dtest=ComputeSpecBuilderJwksTest test
```
Expected: BUILD SUCCESS, both tests pass.

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/k8s/ComputeSpecBuilder.java \
       lakeon-api/src/test/java/com/lakeon/k8s/ComputeSpecBuilderJwksTest.java
git commit -m "feat(api): inject JWKS public key into compute_ctl spec when configured"
```

### Task B1.4: `ComputeJwtSigner` — Java signing utility

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/compute/ComputeJwtSigner.java`
- Create: `lakeon-api/src/test/java/com/lakeon/compute/ComputeJwtSignerTest.java`
- Modify: `lakeon-api/pom.xml` — add JJWT dependency

- [ ] **Step 1: Add JJWT to pom.xml**

In `<dependencies>`:

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 2: Write the failing test**

Create `lakeon-api/src/test/java/com/lakeon/compute/ComputeJwtSignerTest.java`:

```java
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

class ComputeJwtSignerTest {
    private LakeonProperties props;
    private ComputeJwtSigner signer;
    // Test keypair — generated once via openssl, hardcoded here. Not used in prod.
    private static final String TEST_PRIVATE_PEM =
        "-----BEGIN PRIVATE KEY-----\n" +
        // ... paste a real 2048-bit test PEM here when implementing ...
        "-----END PRIVATE KEY-----\n";
    private static final String TEST_PUBLIC_PEM =
        "-----BEGIN PUBLIC KEY-----\n" +
        // ... matching public PEM ...
        "-----END PUBLIC KEY-----\n";

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

        // Verify with public key
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
}
```

- [ ] **Step 3: Run test (expect FAIL — class doesn't exist)**

```bash
mvn -Dtest=ComputeJwtSignerTest test
```
Expected: compile error / class not found.

- [ ] **Step 4: Implement `ComputeJwtSigner`**

Create `lakeon-api/src/main/java/com/lakeon/compute/ComputeJwtSigner.java`:

```java
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
            String body = pem.replaceAll("-----[^-]+-----", "")
                             .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            this.privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load COMPUTE_JWT_PRIVATE_KEY: " + e.getMessage(), e);
        }
    }

    /**
     * Generate a JWT for compute_ctl HTTP API authentication.
     * Subject is the database id (compute_id). Issuer/TTL come from config.
     */
    public String signComputeCtlToken(String computeId) {
        if (privateKey == null) {
            throw new IllegalStateException("ComputeJwtSigner not configured (COMPUTE_JWT_PRIVATE_KEY missing)");
        }
        Instant now = Instant.now();
        return Jwts.builder()
            .header().keyId(props.getComputeJwt().getKid()).and()
            .subject(computeId)
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
```

- [ ] **Step 5: Run test (expect PASS)**

```bash
mvn -Dtest=ComputeJwtSignerTest test
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/pom.xml \
       lakeon-api/src/main/java/com/lakeon/compute/ComputeJwtSigner.java \
       lakeon-api/src/test/java/com/lakeon/compute/ComputeJwtSignerTest.java
git commit -m "feat(api): ComputeJwtSigner for compute_ctl /configure RS256 auth"
```

### Task B1.5: Deploy + smoke test JWT-protected /configure

This is the integration test. After deploy, manually call `/configure` from inside the lakeon-api pod against a real compute pod and confirm it accepts the JWT.

- [ ] **Step 1: Generate keys + push to .env (Task B1.1 already done)**

- [ ] **Step 2: Deploy**

```bash
cd /Users/jacky/code/lakeon
SITE=hwstaff bash deploy/cce/build-and-push-api.sh
SITE=hwstaff bash deploy/cce/deploy.sh --skip-test
```

- [ ] **Step 3: Trigger a cold start to create a fresh compute pod with the new JWKS spec**

```bash
# Wake a DB via dbay memory_recall (any path that hits MemoryDbHelper.wakeCompute)
# After a few seconds, find the compute pod:
KUBECONFIG=~/.kube/cce-lakeon-config kubectl get pods -n lakeon-compute -l app=lakeon-compute --sort-by=.metadata.creationTimestamp
```

Note the most recent pod name.

- [ ] **Step 4: Exec into lakeon-api, generate a token, call /status**

```bash
KUBECONFIG=~/.kube/cce-lakeon-config kubectl exec -n lakeon deploy/lakeon-api -- \
  java -cp /app/lib/'*' -jar /app/app.jar \
  # ... easier: use a debug endpoint we add next
```

This test step depends on having a way to generate a JWT from the pod. Add a temporary debug endpoint (admin-only):

In `AdminController`, add:

```java
@PostMapping("/debug/compute-jwt")
public Map<String, Object> debugComputeJwt(@RequestBody Map<String, String> req) {
    String computeId = req.get("computeId");
    return Map.of("token", computeJwtSigner.signComputeCtlToken(computeId));
}
```

Call it:

```bash
TOKEN=$(KUBECONFIG=~/.kube/cce-lakeon-config kubectl exec -n lakeon deploy/lakeon-api -- \
  curl -sk -X POST https://localhost:8090/api/v1/admin/debug/compute-jwt \
  -H "Authorization: Bearer lakeon-sre-2026" \
  -H "Content-Type: application/json" \
  -d '{"computeId":"compute-db-c7acd26f"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
echo "TOKEN length: ${#TOKEN}"

POD_IP=$(KUBECONFIG=~/.kube/cce-lakeon-config kubectl get pod -n lakeon-compute compute-db-c7acd26f -o jsonpath='{.status.podIP}')

KUBECONFIG=~/.kube/cce-lakeon-config kubectl exec -n lakeon deploy/lakeon-api -- \
  curl -s --max-time 10 "http://$POD_IP:3080/status" \
  -H "Authorization: Bearer $TOKEN"
```

Expected: HTTP 200 with JSON body like `{"start_time":"...","status":"Running",...}`.

If it still returns `{"error":"invalid authorization token"}`, debug:
- Check `compute_ctl` logs for JWT verification errors.
- Confirm spec ConfigMap of the running pod contains the JWK (it was created BEFORE this deploy if cold start happened on old code).
- Delete the pod, trigger a fresh cold start, retest.

- [ ] **Step 5: Remove the debug endpoint**

```java
// Delete the @PostMapping("/debug/compute-jwt") method from AdminController
```

- [ ] **Step 6: Commit + push**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/AdminController.java
git commit -m "chore(api): remove debug compute-jwt endpoint after B1 smoke test"
git push origin main
```

**B1 Definition of Done:**
- `kubectl exec` from lakeon-api can `curl /status` on a compute pod with a signed JWT → HTTP 200.
- Unit tests green: `ComputeSpecBuilderJwksTest`, `ComputeJwtSignerTest`.
- Production deploy still works (no regression in cold start path — `wake breakdown` logs still appearing).

---

## Phase B2: `ComputeWarmPoolManager`

**Goal:** Maintain N idle compute pods in `lakeon-compute` namespace (label `lakeon.io/pool=warm`). Expose `claim(DatabaseEntity)` that picks an idle pod, POSTs the real spec to its `/configure`, returns the claimed pod address. Pool replenishes via 10s `@Scheduled` reconcile.

**Why second:** Builds directly on B1's JWT signer + spec injection.

**Estimate:** 1-2 weeks.

**Resolved design choices** (see "Decisions" at top of doc):
- Pool in `lakeon-compute` ns, label `lakeon.io/pool=warm`.
- Single pool, dominant image; misses fallback to `createComputePod`. Track misses by required image.
- Dummy spec uses a real mock tenant `warm-pool-tenant` provisioned once (B2.2).
- `claim()` blocks on `/configure` (synchronous matches cold path semantics).
- `claim()` enforces 1.5s timeout — beyond that, delete pod and fallback.
- Claimed pods are NEVER reused — deleted after claim, reconcile replenishes.

**File structure:**
```
lakeon-api/src/main/java/com/lakeon/
├── compute/
│   ├── ComputeReconfigureClient.java       # B2.3 — HTTP client for compute_ctl /configure
│   ├── ComputeReconfigureClient$Result.java # nested record (success/timeout/error)
│   ├── ComputeWarmPoolManager.java         # B2.4-B2.7 — pool maintenance + claim/release
│   └── (ComputeJwtSigner.java)             # exists from B1
├── config/
│   └── LakeonProperties.java               # B2.1 — WarmPoolConfig nested class
└── k8s/
    └── ComputePodManager.java              # B2.5 — extract buildPodSpec helper
```

### Sub-task breakdown

| Task | What | Estimate |
|---|---|---|
| **B2.1** | `WarmPoolConfig` in LakeonProperties + helm values + application.yml | 1 day |
| **B2.2** | Provision `warm-pool-tenant` mock tenant on pageserver (operational, scripted) | 0.5 day |
| **B2.3** | `ComputeReconfigureClient` with JWT auth + 1.5s timeout (TDD with MockWebServer) | 2 days |
| **B2.4** | `ComputeWarmPoolManager` skeleton: `@Scheduled reconcile()` maintains N idle pods, `createIdlePod()` uses mock tenant spec | 2 days |
| **B2.5** | `claim(DatabaseEntity)`: optimistic label swap → POST /configure → return ClaimedPod; refactor `ComputePodManager.createComputePod` to share Pod construction via `buildPodSpec` | 3 days |
| **B2.6** | `release(podName)`: delete pod + ConfigMap; reconcile picks up the gap | 1 day |
| **B2.7** | Concurrency + edge case tests: two callers racing on same pod, pool exhaustion, reconcile replenishment | 2 days |
| **B2.8** | Metrics: `lakeon_warm_pool_size`, `_hits`, `_misses`, `_miss_by_image`, `_reconfigure_seconds` (B3 wires the reconfigure_ms phase into wake breakdown log) | 1 day |

---

### Task B2.1: `WarmPoolConfig` in LakeonProperties + helm values

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`
- Modify: `lakeon-api/src/main/resources/application.yml`
- Modify: `deploy/helm/lakeon/values.yaml`

**No helm secret needed** — warm pool config is non-sensitive (size, namespace label, mock tenant id, image, enabled flag).

- [ ] **Step 1: Add `ComputeWarmPoolConfig` nested class to LakeonProperties**

Follow the existing nested class style (manual getters/setters, like `ComputeJwtConfig`). After the existing `ComputeJwtConfig` class:

```java
/**
 * Warm pool configuration. When enabled, lakeon-api maintains N idle
 * compute pods labeled `lakeon.io/pool=warm` in the lakeon-compute
 * namespace. Cold starts try to claim one (POST /configure with the
 * real spec, ~500ms reconfigure) before falling back to creating a
 * fresh Pod (~2s). Plan: docs/superpowers/plans/2026-05-16-compute-warm-pool.md
 */
public static class ComputeWarmPoolConfig {
    private boolean enabled = false;
    private int size = 2;
    private String podLabelValue = "warm";
    private String mockTenantId = "";
    private String mockTimelineId = "";
    private String image = "";  // empty = use default compute image
    private int reconfigureTimeoutMs = 1500;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public String getPodLabelValue() { return podLabelValue; }
    public void setPodLabelValue(String v) { this.podLabelValue = v; }
    public String getMockTenantId() { return mockTenantId; }
    public void setMockTenantId(String v) { this.mockTenantId = v; }
    public String getMockTimelineId() { return mockTimelineId; }
    public void setMockTimelineId(String v) { this.mockTimelineId = v; }
    public String getImage() { return image; }
    public void setImage(String v) { this.image = v; }
    public int getReconfigureTimeoutMs() { return reconfigureTimeoutMs; }
    public void setReconfigureTimeoutMs(int v) { this.reconfigureTimeoutMs = v; }
}

private ComputeWarmPoolConfig computeWarmPool = new ComputeWarmPoolConfig();

public ComputeWarmPoolConfig getComputeWarmPool() { return computeWarmPool; }
public void setComputeWarmPool(ComputeWarmPoolConfig v) { this.computeWarmPool = v; }
```

- [ ] **Step 2: Bind in `application.yml`**

Under the existing `lakeon:` section:

```yaml
lakeon:
  compute-warm-pool:
    enabled: ${COMPUTE_WARM_POOL_ENABLED:false}
    size: ${COMPUTE_WARM_POOL_SIZE:2}
    pod-label-value: warm
    mock-tenant-id: ${COMPUTE_WARM_POOL_MOCK_TENANT_ID:}
    mock-timeline-id: ${COMPUTE_WARM_POOL_MOCK_TIMELINE_ID:}
    image: ${COMPUTE_WARM_POOL_IMAGE:}
    reconfigure-timeout-ms: 1500
```

- [ ] **Step 3: Update `deploy/helm/lakeon/values.yaml`**

Add top-level:

```yaml
computeWarmPool:
  enabled: false
  size: 2
  mockTenantId: ""
  mockTimelineId: ""
  image: ""
```

- [ ] **Step 4: Wire helm → pod env**

In `deploy/helm/lakeon/templates/deployment-api.yaml`, find the api container's `env:` and add (after the existing `COMPUTE_JWT_*` entries from B1):

```yaml
- name: COMPUTE_WARM_POOL_ENABLED
  value: {{ .Values.computeWarmPool.enabled | quote }}
- name: COMPUTE_WARM_POOL_SIZE
  value: {{ .Values.computeWarmPool.size | quote }}
- name: COMPUTE_WARM_POOL_MOCK_TENANT_ID
  value: {{ .Values.computeWarmPool.mockTenantId | quote }}
- name: COMPUTE_WARM_POOL_MOCK_TIMELINE_ID
  value: {{ .Values.computeWarmPool.mockTimelineId | quote }}
- name: COMPUTE_WARM_POOL_IMAGE
  value: {{ .Values.computeWarmPool.image | quote }}
```

(Match the multi-line block style used by the OBS env entries — not flow style — per the code review feedback from B1.2.)

- [ ] **Step 5: Compile sanity**

```bash
cd /Users/jacky/code/lakeon/lakeon-api && mvn -DskipTests compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java \
       lakeon-api/src/main/resources/application.yml \
       deploy/helm/lakeon/values.yaml \
       deploy/helm/lakeon/templates/deployment-api.yaml
git commit -m "chore(api): wire COMPUTE_WARM_POOL_* config (B2.1, disabled by default)"
```

**Tasks B2.2–B2.8 detailed when each starts.** Same pattern as B1: write the next task's bite-sized steps right before dispatching.

---

## Phase B3: Integration into `ComputeLifecycleService`

**Goal:** Cold path in `doWakeCompute` tries warm-claim first, falls back to `createComputePod` on miss.

**Estimate:** 3-5 days.

**Key change in `ComputeLifecycleService.doWakeCompute` cold path (after the pageserver-wait + pre-write block):**

```java
// Try warm pool first
Optional<ComputeWarmPoolManager.ClaimedPod> claimed = Optional.empty();
if (warmPoolManager.isEnabled()) {
    claimed = warmPoolManager.claim(entity);
}

String address;
ComputePodManager.PodReadyPhases phases;
if (claimed.isPresent()) {
    // Warm path
    String podName = claimed.get().podName();
    String podIp = claimed.get().podIp();
    address = podIp + ":55433";
    // Update entity, persist, no waitForPodReady (claim already POSTed /configure synchronously)
    phases = new ComputePodManager.PodReadyPhases(-1, -1, -1, -1, true);
} else {
    // Cold path — existing code
    address = computePodManager.createComputePod(entity);
    phases = computePodManager.waitForPodReadyTimed(podName, DEFAULT_WAKE_TIMEOUT_MS);
}
```

**Tasks:**
1. Add `ComputeWarmPoolManager` dependency to constructor.
2. Modify cold path with the if/else branch.
3. Extend `wake breakdown` log with a `path=warm|cold` field.
4. Add `reconfigure_ms` field when path=warm.
5. Update unit tests to cover warm-claim success, warm-claim returning empty (fallthrough), and reconfigure failure (fallthrough).

**Definition of done:** E2E production trace shows `wake breakdown ... path=warm reconfigure=420ms ... total=560ms` for a warm-claimed db.

---

## Phase B4: Observability + E2E

**Goal:** Make warm pool hits and misses visible in production. E2E coverage so we don't regress.

**Estimate:** 3-5 days.

**Tasks:**
1. **Metrics** (`MeterRegistry`):
   - `lakeon_warm_pool_size` (gauge) — current idle pod count
   - `lakeon_warm_pool_hits` (counter) — claims that succeeded
   - `lakeon_warm_pool_misses` (counter) — claims that fell through
   - `lakeon_warm_pool_reconfigure_seconds` (timer) — `/configure` latency
2. **Admin endpoint** `/api/v1/admin/warm-pool/status` returning current pool state.
3. **E2E test** `tests/e2e/test_warm_pool.py` (Python pytest):
   - Suspend a db.
   - Wait for pool to replenish.
   - Wake the db.
   - Assert `path=warm` in lakeon-api logs and total time < 1s.
4. **Grafana / lakeon-admin dashboard** updates (frontend follow-up if needed).

---

## Phase B5: Production rollout

**Goal:** Turn it on without breaking anything.

**Estimate:** 3-5 days of monitoring + tuning.

**Tasks:**
1. Deploy with `warmPool.enabled=true, warmPool.size=2`. Watch for 24h.
2. If steady-state, scale to size=5 (or whatever matches observed cold start frequency).
3. Verify `lakeon_warm_pool_hit_rate` > 80% over rolling window.
4. Document operational runbook (`docs/runbooks/warm-pool.md`):
   - How to disable in an emergency (`helm --set warmPool.enabled=false`)
   - How to bump size
   - Known failure modes

**Definition of done overall:** `p50(total wake time)` < 800ms over a 24h window. `path=warm` hit rate > 80%.

---

## Risks

- **`/configure` actual latency unknown.** PoC measured cold-init at 382ms; reconfigure on a *running* pod may behave differently. Mitigation: B1's smoke test gives the first real number; rework if reconfigure > 800ms.
- **Mock tenant on pageserver.** Needs a real tenant with no data. Costs minimal pageserver storage but the operational footprint of "this tenant is special, don't touch" needs documenting. Mitigation: add to `docs/runbooks/`.
- **JWT key rotation.** Today's plan injects one static keypair. Rotating requires re-rolling all running compute pods (their spec embeds the JWKS). Mitigation: design supports multiple keys in JWKS list — add new key, deploy, wait for pods to recycle, remove old key. Document in B5 runbook.
- **CCE node capacity.** N idle pods (each ~500MB+0.5CPU) consume cluster resources whether or not anyone claims them. Mitigation: start with size=2, scale based on hit rate.
- **Multi-image divergence.** 4 compute images exist (pgsearch, pgsearch-neon, pgsearch-ctl, zhparser). B2 covers only the dominant image. Mitigation: track misses by required image label; if a second image becomes hot, add a second pool.
- **Tenant data leak.** Claim-once policy (delete after claim) preserves today's isolation. If anyone later proposes pod reuse for cost savings: NACK without a full review of PGDATA + temp files + log handling.

## Open Questions (to resolve before each phase)

| Question | Resolved when |
|---|---|
| Mock tenant identity / how to provision | Before B2 |
| Multi-image pool strategy | Before B2 |
| `/configure` real latency on warm pod | After B1 smoke test |
| Pool-miss fallback behavior | Confirmed in plan; revisit if metrics show too many misses |
| TLS vs HTTP for `/configure` | Before B2 |
| Per-image pool sizing | After B5 first week of data |

## Out of Scope (for this plan)

- CRIU-based snapshot restore (different architectural approach; revisit only if warm pool doesn't hit <1s for GB-scale DBs).
- Replacing notebook `WarmPoolManager` — it serves Ray, leave it.
- Frontend changes for warm pool admin UI (lakeon-admin) — may follow in a separate plan.
- Auto-scaling pool size based on demand prediction — manual sizing for now, automate later.
