# Google + GitHub OAuth Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Google and GitHub OAuth login so users can sign in with their Google or GitHub account.

**Architecture:** Backend handles the full OAuth Authorization Code flow — redirects to provider, exchanges code for token, fetches user info, creates/finds tenant, issues a temporary auth code that the frontend exchanges for an API key. No Spring Security OAuth2 module needed — we use plain HTTP calls to keep it lightweight and consistent with the existing custom filter approach.

**Tech Stack:** Spring Boot 3.3.5 (Java 17), Vue 3 + TypeScript, PostgreSQL (Flyway migration V34)

---

### Task 1: Database Migration — oauth_connections table + tenants.email

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V34__create_oauth_connections.sql`

- [ ] **Step 1: Write the Flyway migration**

```sql
-- V34__create_oauth_connections.sql

-- Add email and avatar to tenants
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(512);

-- OAuth provider connections
CREATE TABLE oauth_connections (
    id VARCHAR(32) PRIMARY KEY,
    tenant_id VARCHAR(32) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    display_name VARCHAR(255),
    avatar_url VARCHAR(512),
    access_token VARCHAR(512),
    refresh_token VARCHAR(512),
    scope VARCHAR(512),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT uq_oauth_provider_user UNIQUE(provider, provider_user_id)
);

CREATE INDEX idx_oauth_connections_tenant ON oauth_connections(tenant_id);
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V34__create_oauth_connections.sql
git commit -m "feat(api): add V34 migration for oauth_connections table"
```

---

### Task 2: Backend — OAuthConnection Entity + Repository

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/model/entity/OAuthConnectionEntity.java`
- Create: `lakeon-api/src/main/java/com/lakeon/repository/OAuthConnectionRepository.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/model/entity/TenantEntity.java`

- [ ] **Step 1: Add email and avatarUrl fields to TenantEntity**

Add these fields after the `passwordHash` field in `TenantEntity.java`:

```java
@Column(name = "email", length = 255)
private String email;

@Column(name = "avatar_url", length = 512)
private String avatarUrl;
```

And their getters/setters:

```java
public String getEmail() { return email; }
public void setEmail(String email) { this.email = email; }

public String getAvatarUrl() { return avatarUrl; }
public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
```

- [ ] **Step 2: Create OAuthConnectionEntity**

```java
package com.lakeon.model.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "oauth_connections")
public class OAuthConnectionEntity {

    @Id
    @Column(name = "id", length = 32)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "avatar_url", length = 512)
    private String avatarUrl;

    @Column(name = "access_token", length = 512)
    private String accessToken;

    @Column(name = "refresh_token", length = 512)
    private String refreshToken;

    @Column(name = "scope", length = 512)
    private String scope;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = "oc_" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderUserId() { return providerUserId; }
    public void setProviderUserId(String providerUserId) { this.providerUserId = providerUserId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 3: Create OAuthConnectionRepository**

```java
package com.lakeon.repository;

import com.lakeon.model.entity.OAuthConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OAuthConnectionRepository extends JpaRepository<OAuthConnectionEntity, String> {
    Optional<OAuthConnectionEntity> findByProviderAndProviderUserId(String provider, String providerUserId);
    List<OAuthConnectionEntity> findAllByTenantId(String tenantId);
}
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/model/entity/OAuthConnectionEntity.java \
        lakeon-api/src/main/java/com/lakeon/repository/OAuthConnectionRepository.java \
        lakeon-api/src/main/java/com/lakeon/model/entity/TenantEntity.java
git commit -m "feat(api): add OAuthConnection entity and repository"
```

---

### Task 3: Backend — OAuth Configuration

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java`
- Modify: `lakeon-api/src/main/resources/application.yml`

- [ ] **Step 1: Add OAuthConfig to LakeonProperties**

Add the field in `LakeonProperties.java` after the `WikiConfig wiki` field:

```java
private OAuthConfig oauth = new OAuthConfig();

public OAuthConfig getOauth() { return oauth; }
public void setOauth(OAuthConfig oauth) { this.oauth = oauth; }
```

Add the inner classes at the bottom of `LakeonProperties.java`:

```java
public static class OAuthConfig {
    private OAuthProviderConfig google = new OAuthProviderConfig();
    private OAuthProviderConfig github = new OAuthProviderConfig();
    private String callbackBaseUrl = "";

    public OAuthProviderConfig getGoogle() { return google; }
    public void setGoogle(OAuthProviderConfig google) { this.google = google; }
    public OAuthProviderConfig getGithub() { return github; }
    public void setGithub(OAuthProviderConfig github) { this.github = github; }
    public String getCallbackBaseUrl() { return callbackBaseUrl; }
    public void setCallbackBaseUrl(String callbackBaseUrl) { this.callbackBaseUrl = callbackBaseUrl; }
}

public static class OAuthProviderConfig {
    private String clientId = "";
    private String clientSecret = "";

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
}
```

- [ ] **Step 2: Add OAuth config to application.yml**

Add under the `lakeon:` section:

```yaml
  oauth:
    callback-base-url: ${LAKEON_OAUTH_CALLBACK_BASE_URL:http://localhost:8080}
    google:
      client-id: ${LAKEON_OAUTH_GOOGLE_CLIENT_ID:}
      client-secret: ${LAKEON_OAUTH_GOOGLE_CLIENT_SECRET:}
    github:
      client-id: ${LAKEON_OAUTH_GITHUB_CLIENT_ID:}
      client-secret: ${LAKEON_OAUTH_GITHUB_CLIENT_SECRET:}
```

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/config/LakeonProperties.java \
        lakeon-api/src/main/resources/application.yml
git commit -m "feat(api): add OAuth provider configuration"
```

---

### Task 4: Backend — OAuthService (core logic)

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/service/OAuthService.java`

This service handles:
1. Building the authorization URL for each provider
2. Exchanging the authorization code for tokens
3. Fetching user info from the provider
4. Finding or creating a tenant
5. Generating a temporary auth code for the frontend

- [ ] **Step 1: Create OAuthService**

```java
package com.lakeon.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.ApiKeyEntity;
import com.lakeon.model.entity.OAuthConnectionEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.ApiKeyRepository;
import com.lakeon.repository.OAuthConnectionRepository;
import com.lakeon.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OAuthService {
    private static final Logger log = LoggerFactory.getLogger(OAuthService.class);
    private static final long AUTH_CODE_TTL_MS = 5 * 60 * 1000; // 5 minutes

    private final LakeonProperties props;
    private final OAuthConnectionRepository oauthRepo;
    private final TenantRepository tenantRepo;
    private final ApiKeyRepository apiKeyRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // Temporary auth codes: code -> {tenantId, expiresAt}
    private final ConcurrentHashMap<String, AuthCodeEntry> authCodes = new ConcurrentHashMap<>();

    public OAuthService(LakeonProperties props,
                        OAuthConnectionRepository oauthRepo,
                        TenantRepository tenantRepo,
                        ApiKeyRepository apiKeyRepo) {
        this.props = props;
        this.oauthRepo = oauthRepo;
        this.tenantRepo = tenantRepo;
        this.apiKeyRepo = apiKeyRepo;
    }

    // ── Authorization URL builders ──

    public String getGoogleAuthUrl(String state) {
        var google = props.getOauth().getGoogle();
        String redirectUri = getCallbackUrl("google");
        return "https://accounts.google.com/o/oauth2/v2/auth"
                + "?client_id=" + enc(google.getClientId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc("openid email profile")
                + "&state=" + enc(state)
                + "&access_type=offline"
                + "&prompt=select_account";
    }

    public String getGithubAuthUrl(String state) {
        var github = props.getOauth().getGithub();
        String redirectUri = getCallbackUrl("github");
        return "https://github.com/login/oauth/authorize"
                + "?client_id=" + enc(github.getClientId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&scope=" + enc("user:email")
                + "&state=" + enc(state);
    }

    // ── Callback handlers ──

    @Transactional
    public String handleGoogleCallback(String code) throws Exception {
        var google = props.getOauth().getGoogle();
        String redirectUri = getCallbackUrl("google");

        // Exchange code for tokens
        String tokenBody = "code=" + enc(code)
                + "&client_id=" + enc(google.getClientId())
                + "&client_secret=" + enc(google.getClientSecret())
                + "&redirect_uri=" + enc(redirectUri)
                + "&grant_type=authorization_code";

        JsonNode tokenResp = postForm("https://oauth2.googleapis.com/token", tokenBody);
        String accessToken = tokenResp.get("access_token").asText();

        // Fetch user info
        JsonNode userInfo = getJson("https://www.googleapis.com/oauth2/v2/userinfo",
                "Bearer " + accessToken);

        String providerUserId = userInfo.get("id").asText();
        String email = userInfo.has("email") ? userInfo.get("email").asText() : null;
        String name = userInfo.has("name") ? userInfo.get("name").asText() : email;
        String avatar = userInfo.has("picture") ? userInfo.get("picture").asText() : null;

        return findOrCreateTenant("google", providerUserId, email, name, avatar, accessToken, null,
                "openid email profile");
    }

    @Transactional
    public String handleGithubCallback(String code) throws Exception {
        var github = props.getOauth().getGithub();
        String redirectUri = getCallbackUrl("github");

        // Exchange code for token
        String tokenBody = "code=" + enc(code)
                + "&client_id=" + enc(github.getClientId())
                + "&client_secret=" + enc(github.getClientSecret())
                + "&redirect_uri=" + enc(redirectUri);

        // GitHub token endpoint needs Accept: application/json
        HttpRequest tokenReq = HttpRequest.newBuilder()
                .uri(URI.create("https://github.com/login/oauth/access_token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
                .build();
        HttpResponse<String> tokenResp = httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString());
        JsonNode tokenJson = objectMapper.readTree(tokenResp.body());
        String accessToken = tokenJson.get("access_token").asText();

        // Fetch user info
        JsonNode userInfo = getJson("https://api.github.com/user", "Bearer " + accessToken);
        String providerUserId = String.valueOf(userInfo.get("id").asLong());
        String name = userInfo.has("name") && !userInfo.get("name").isNull()
                ? userInfo.get("name").asText() : userInfo.get("login").asText();
        String avatar = userInfo.has("avatar_url") ? userInfo.get("avatar_url").asText() : null;
        String login = userInfo.get("login").asText();

        // GitHub user email may be private — fetch from /user/emails
        String email = userInfo.has("email") && !userInfo.get("email").isNull()
                ? userInfo.get("email").asText() : null;
        if (email == null) {
            JsonNode emails = getJson("https://api.github.com/user/emails", "Bearer " + accessToken);
            for (JsonNode e : emails) {
                if (e.has("primary") && e.get("primary").asBoolean()) {
                    email = e.get("email").asText();
                    break;
                }
            }
        }

        return findOrCreateTenant("github", providerUserId, email,
                name != null ? name : login, avatar, accessToken, null, "user:email");
    }

    // ── Temporary auth code exchange ──

    public String exchangeAuthCode(String authCode) {
        AuthCodeEntry entry = authCodes.remove(authCode);
        if (entry == null || System.currentTimeMillis() > entry.expiresAt) {
            return null;
        }
        return entry.tenantId;
    }

    // ── Internal helpers ──

    private String findOrCreateTenant(String provider, String providerUserId, String email,
                                       String displayName, String avatarUrl,
                                       String accessToken, String refreshToken, String scope) {
        // Check if OAuth connection already exists
        var existing = oauthRepo.findByProviderAndProviderUserId(provider, providerUserId);
        TenantEntity tenant;

        if (existing.isPresent()) {
            // Update tokens
            OAuthConnectionEntity conn = existing.get();
            conn.setAccessToken(accessToken);
            if (refreshToken != null) conn.setRefreshToken(refreshToken);
            conn.setEmail(email);
            conn.setDisplayName(displayName);
            conn.setAvatarUrl(avatarUrl);
            oauthRepo.save(conn);

            tenant = tenantRepo.findById(conn.getTenantId()).orElse(null);
            if (tenant == null) {
                throw new RuntimeException("Tenant not found for OAuth connection: " + conn.getTenantId());
            }
        } else {
            // Try to match by email to an existing tenant
            tenant = email != null ? tenantRepo.findByEmail(email).orElse(null) : null;

            if (tenant == null) {
                // Create new tenant
                tenant = new TenantEntity();
                String username = generateUniqueUsername(provider, displayName, providerUserId);
                tenant.setUsername(username);
                tenant.setName(displayName != null ? displayName : username);
                tenant.setEmail(email);
                tenant.setAvatarUrl(avatarUrl);
                // No password — OAuth-only user
                tenant = tenantRepo.save(tenant);

                // Create default API key
                ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
                apiKeyEntity.setTenantId(tenant.getId());
                apiKeyEntity.setName("Default");
                apiKeyEntity.setApiKey(tenant.getApiKey());
                apiKeyRepo.save(apiKeyEntity);
            } else {
                // Update avatar if not set
                if (tenant.getAvatarUrl() == null && avatarUrl != null) {
                    tenant.setAvatarUrl(avatarUrl);
                    tenantRepo.save(tenant);
                }
            }

            // Create OAuth connection
            OAuthConnectionEntity conn = new OAuthConnectionEntity();
            conn.setTenantId(tenant.getId());
            conn.setProvider(provider);
            conn.setProviderUserId(providerUserId);
            conn.setEmail(email);
            conn.setDisplayName(displayName);
            conn.setAvatarUrl(avatarUrl);
            conn.setAccessToken(accessToken);
            conn.setRefreshToken(refreshToken);
            conn.setScope(scope);
            oauthRepo.save(conn);
        }

        if (Boolean.TRUE.equals(tenant.getDisabled())) {
            throw new RuntimeException("Account is disabled");
        }

        // Generate temporary auth code
        String authCode = generateAuthCode();
        authCodes.put(authCode, new AuthCodeEntry(tenant.getId(), System.currentTimeMillis() + AUTH_CODE_TTL_MS));
        return authCode;
    }

    private String generateUniqueUsername(String provider, String displayName, String providerUserId) {
        // Try display name first (sanitized), then fall back to provider_id
        String base = displayName != null
                ? displayName.toLowerCase().replaceAll("[^a-z0-9_-]", "")
                : provider + "_" + providerUserId;
        if (base.isEmpty()) base = provider + "_" + providerUserId;

        String candidate = base;
        int suffix = 1;
        while (tenantRepo.findByUsername(candidate).isPresent()) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }

    private String generateAuthCode() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String getCallbackUrl(String provider) {
        String base = props.getOauth().getCallbackBaseUrl();
        return base + "/api/v1/auth/oauth/" + provider + "/callback";
    }

    private JsonNode postForm(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private JsonNode getJson(String url, String auth) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", auth)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private record AuthCodeEntry(String tenantId, long expiresAt) {}
}
```

- [ ] **Step 2: Add `findByEmail` to TenantRepository**

Add this method to `TenantRepository.java`:

```java
Optional<TenantEntity> findByEmail(String email);
```

- [ ] **Step 3: Compile and verify**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/OAuthService.java \
        lakeon-api/src/main/java/com/lakeon/repository/TenantRepository.java
git commit -m "feat(api): add OAuthService with Google and GitHub login support"
```

---

### Task 5: Backend — OAuth Controller + Filter exclusions

**Files:**
- Create: `lakeon-api/src/main/java/com/lakeon/controller/OAuthController.java`
- Modify: `lakeon-api/src/main/java/com/lakeon/config/ApiKeyFilter.java`

- [ ] **Step 1: Create OAuthController**

```java
package com.lakeon.controller;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.dto.TenantResponse;
import com.lakeon.service.OAuthService;
import com.lakeon.service.TenantService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth/oauth")
public class OAuthController {
    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    private final OAuthService oauthService;
    private final TenantService tenantService;
    private final LakeonProperties props;

    public OAuthController(OAuthService oauthService, TenantService tenantService, LakeonProperties props) {
        this.oauthService = oauthService;
        this.tenantService = tenantService;
        this.props = props;
    }

    /**
     * Step 1: Redirect user to OAuth provider's authorization page.
     * GET /api/v1/auth/oauth/{provider}?redirect_uri=https://console.dbay.cloud/oauth/callback
     */
    @GetMapping("/{provider}")
    public void authorize(@PathVariable String provider,
                          @RequestParam(name = "redirect_uri", defaultValue = "") String redirectUri,
                          HttpServletResponse response) throws IOException {
        String state = generateState() + "|" + redirectUri;
        String authUrl = switch (provider) {
            case "google" -> oauthService.getGoogleAuthUrl(state);
            case "github" -> oauthService.getGithubAuthUrl(state);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown provider: " + provider);
        };
        response.sendRedirect(authUrl);
    }

    /**
     * Step 2: Provider redirects back here after user authorizes.
     * GET /api/v1/auth/oauth/{provider}/callback?code=xxx&state=yyy
     */
    @GetMapping("/{provider}/callback")
    public void callback(@PathVariable String provider,
                         @RequestParam String code,
                         @RequestParam(required = false) String state,
                         HttpServletResponse response) throws IOException {
        try {
            String authCode = switch (provider) {
                case "google" -> oauthService.handleGoogleCallback(code);
                case "github" -> oauthService.handleGithubCallback(code);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown provider: " + provider);
            };

            // Extract redirect_uri from state
            String redirectUri = extractRedirectUri(state);
            if (redirectUri.isEmpty()) {
                // Default: console OAuth callback page
                redirectUri = props.getOauth().getCallbackBaseUrl().replace("/api/v1", "")
                        .replace(":8080", ":5173"); // dev fallback
                if (redirectUri.endsWith("8443")) {
                    redirectUri = "https://console.dbay.cloud";
                }
                redirectUri += "/oauth/callback";
            }

            // Redirect to frontend with auth code
            String separator = redirectUri.contains("?") ? "&" : "?";
            response.sendRedirect(redirectUri + separator + "code=" + authCode + "&provider=" + provider);
        } catch (Exception e) {
            log.error("OAuth callback failed for provider={}", provider, e);
            response.sendRedirect("/login?error=oauth_failed");
        }
    }

    /**
     * Step 3: Frontend exchanges temp auth code for API key.
     * POST /api/v1/auth/oauth/token { "code": "xxx" }
     */
    @PostMapping("/token")
    public TenantResponse exchangeToken(@RequestBody Map<String, String> body) {
        String authCode = body.get("code");
        if (authCode == null || authCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing auth code");
        }

        String tenantId = oauthService.exchangeAuthCode(authCode);
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired auth code");
        }

        return tenantService.get(tenantId);
    }

    private String extractRedirectUri(String state) {
        if (state == null) return "";
        int pipe = state.indexOf('|');
        return pipe >= 0 ? state.substring(pipe + 1) : "";
    }

    private String generateState() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
```

- [ ] **Step 2: Add OAuth endpoints to ApiKeyFilter exclusions**

In `ApiKeyFilter.java`, add this block after the `check-username` exclusion (after line 113):

```java
// OAuth endpoints (public, no auth)
if (path.startsWith("/api/v1/auth/oauth/")) {
    chain.doFilter(req, res);
    return;
}
```

- [ ] **Step 3: Compile and verify**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/OAuthController.java \
        lakeon-api/src/main/java/com/lakeon/config/ApiKeyFilter.java
git commit -m "feat(api): add OAuth controller and filter exclusions"
```

---

### Task 6: Backend — Include API key in OAuth token response

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/service/TenantService.java`

The `TenantService.get()` method currently returns the tenant via `toResponseWithDisabled()` which doesn't include the `api_key` in the response (it's omitted by design for the `/tenants/{id}` endpoint). But for OAuth login, we need the API key in the response — same as the `login()` method does via `toResponse()`.

- [ ] **Step 1: Add a `getForLogin` method to TenantService**

Add this method after the existing `get()` method:

```java
public TenantResponse getForLogin(String tenantId) {
    TenantEntity entity = tenantRepository.findById(tenantId)
        .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantId));
    return toResponse(entity);
}
```

- [ ] **Step 2: Update OAuthController to use `getForLogin`**

In `OAuthController.java`, change the `exchangeToken` method to call `tenantService.getForLogin(tenantId)` instead of `tenantService.get(tenantId)`.

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/service/TenantService.java \
        lakeon-api/src/main/java/com/lakeon/controller/OAuthController.java
git commit -m "feat(api): add getForLogin to include api_key in OAuth token exchange"
```

---

### Task 7: Frontend — OAuth callback page + auth store

**Files:**
- Create: `lakeon-console/src/views/login/OAuthCallbackView.vue`
- Modify: `lakeon-console/src/stores/auth.ts`
- Modify: `lakeon-console/src/api/tenant.ts`
- Modify: `lakeon-console/src/router/index.ts`

- [ ] **Step 1: Add OAuth API to tenant.ts**

Add to the `tenantApi` object in `lakeon-console/src/api/tenant.ts`:

```typescript
// OAuth
oauthExchangeToken: (code: string) =>
  client.post<Tenant>('/auth/oauth/token', { code }),
```

- [ ] **Step 2: Add `loginWithOAuthCode` to auth store**

Add this method to `lakeon-console/src/stores/auth.ts`, inside the `defineStore` callback, after the `login` function:

```typescript
async function loginWithOAuthCode(code: string): Promise<{ ok: boolean; error?: string }> {
  try {
    const res = await tenantApi.oauthExchangeToken(code)
    const tenant = res.data
    const key = tenant?.api_key
    if (!key) return { ok: false, error: 'OAuth 登录失败' }

    apiKey.value = key
    localStorage.setItem('lakeon_api_key', key)
    if (tenant?.id) {
      setTenant(tenant.id, tenant.name || '')
    }
    if (tenant?.username) {
      username.value = tenant.username
      localStorage.setItem('lakeon_username', tenant.username)
    }
    setTrialState(false)
    return { ok: true }
  } catch (e: any) {
    if (e.response?.status === 401) {
      return { ok: false, error: '授权码无效或已过期' }
    }
    return { ok: false, error: '网络错误，请稍后重试' }
  }
}
```

Update the return statement to include the new method:

```typescript
return { apiKey, tenantId, tenantName, username, isTrial, trialExpiresAt, login, loginWithOAuthCode, setTenant, setTrialState, logout }
```

- [ ] **Step 3: Create OAuthCallbackView.vue**

```vue
<template>
  <div class="oauth-callback">
    <div class="callback-card">
      <div v-if="error" class="error-state">
        <div class="error-icon">!</div>
        <p class="error-msg">{{ error }}</p>
        <button class="retry-btn" @click="goToLogin">返回登录</button>
      </div>
      <div v-else class="loading-state">
        <div class="spinner"></div>
        <p class="loading-text">正在完成登录...</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../../stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const error = ref('')

onMounted(async () => {
  const code = route.query.code as string
  if (!code) {
    error.value = '缺少授权码'
    return
  }

  const result = await authStore.loginWithOAuthCode(code)
  if (result.ok) {
    router.push('/dashboard')
  } else {
    error.value = result.error || '登录失败'
  }
})

function goToLogin() {
  router.push('/login')
}
</script>

<style scoped>
.oauth-callback {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--pub-bg);
}

.callback-card {
  text-align: center;
  padding: 48px;
}

.loading-state { display: flex; flex-direction: column; align-items: center; gap: 16px; }

.spinner {
  width: 32px;
  height: 32px;
  border: 3px solid var(--pub-border);
  border-top-color: var(--pub-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }

.loading-text { font-size: 15px; color: var(--pub-text-2); }

.error-state { display: flex; flex-direction: column; align-items: center; gap: 12px; }

.error-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: rgba(239, 68, 68, 0.08);
  color: #ef4444;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  font-weight: 700;
}

.error-msg { font-size: 15px; color: var(--pub-text-2); }

.retry-btn {
  margin-top: 8px;
  padding: 10px 32px;
  background: var(--pub-primary);
  color: #fff;
  border: none;
  border-radius: 100px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.retry-btn:hover { opacity: 0.92; }
</style>
```

- [ ] **Step 4: Add route for OAuth callback**

In `lakeon-console/src/router/index.ts`, add after the `ExtCallback` route (around line 27):

```typescript
{
  path: '/oauth/callback',
  name: 'OAuthCallback',
  component: () => import('../views/login/OAuthCallbackView.vue'),
  meta: { noAuth: true },
},
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/src/views/login/OAuthCallbackView.vue \
        lakeon-console/src/stores/auth.ts \
        lakeon-console/src/api/tenant.ts \
        lakeon-console/src/router/index.ts
git commit -m "feat(console): add OAuth callback page and auth store integration"
```

---

### Task 8: Frontend — Add OAuth buttons to login page

**Files:**
- Modify: `lakeon-console/src/views/login/LoginView.vue`

- [ ] **Step 1: Add OAuth buttons to the login form**

In `LoginView.vue`, add the following block after the `<div class="login-footer">` closing `</div>` but before the closing `</div>` of `.form-card` (after line 114):

```html
<!-- OAuth Login -->
<div class="oauth-section">
  <div class="oauth-divider">
    <span class="divider-line"></span>
    <span class="divider-text">或</span>
    <span class="divider-line"></span>
  </div>
  <div class="oauth-buttons">
    <button class="oauth-btn google-btn" @click="loginWithGoogle" type="button">
      <svg class="oauth-icon" viewBox="0 0 24 24" width="18" height="18">
        <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4"/>
        <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/>
        <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05"/>
        <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/>
      </svg>
      <span>Google 登录</span>
    </button>
    <button class="oauth-btn github-btn" @click="loginWithGithub" type="button">
      <svg class="oauth-icon" viewBox="0 0 24 24" width="18" height="18">
        <path d="M12 1C5.37 1 0 6.37 0 13c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0 0 24 13c0-6.63-5.37-12-12-12z" fill="currentColor"/>
      </svg>
      <span>GitHub 登录</span>
    </button>
  </div>
</div>
```

- [ ] **Step 2: Add OAuth methods to script**

Add these functions in the `<script setup>` section, after `goToLogin()`:

```typescript
function loginWithGoogle() {
  const redirectUri = encodeURIComponent(window.location.origin + '/oauth/callback')
  window.location.href = `https://api.dbay.cloud:8443/api/v1/auth/oauth/google?redirect_uri=${redirectUri}`
}

function loginWithGithub() {
  const redirectUri = encodeURIComponent(window.location.origin + '/oauth/callback')
  window.location.href = `https://api.dbay.cloud:8443/api/v1/auth/oauth/github?redirect_uri=${redirectUri}`
}
```

- [ ] **Step 3: Add OAuth styles**

Add these styles at the end of `<style scoped>`:

```css
/* ── OAuth Section ── */
.oauth-section {
  margin-top: 24px;
}

.oauth-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.divider-line {
  flex: 1;
  height: 1px;
  background: var(--pub-border);
}

.divider-text {
  font-size: 12px;
  color: var(--pub-text-4, #aaa);
  white-space: nowrap;
}

.oauth-buttons {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.oauth-btn {
  width: 100%;
  height: 42px;
  border: 1px solid var(--pub-border);
  border-radius: 100px;
  background: var(--pub-surface);
  color: var(--pub-text);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: all 0.25s ease;
}

.oauth-btn:hover {
  border-color: var(--pub-text-3);
  background: var(--pub-hover);
}

.oauth-icon {
  flex-shrink: 0;
}
```

- [ ] **Step 4: Type check**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit`
Expected: No errors

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/src/views/login/LoginView.vue
git commit -m "feat(console): add Google and GitHub OAuth buttons to login page"
```

---

### Task 9: Frontend — Add /oauth/callback to response interceptor public paths

**Files:**
- Modify: `lakeon-console/src/api/client.ts`

- [ ] **Step 1: Add /oauth to public paths**

In `client.ts`, add `'/oauth'` to the `publicPaths` array in the response interceptor:

```typescript
const publicPaths = ['/login', '/landing', '/ext-login', '/ext-callback', '/oauth', '/integrations', '/blog', '/docs', '/product']
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/api/client.ts
git commit -m "fix(console): add /oauth to public paths in response interceptor"
```

---

### Task 10: Backend — Add OAuth rate limiting

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/config/RateLimitFilter.java`

- [ ] **Step 1: Check existing rate limit filter**

Read `RateLimitFilter.java` to understand the rate limiting approach, then add rate limiting for OAuth endpoints:
- `GET /api/v1/auth/oauth/*/callback`: 20 req/min per IP (callback from provider)
- `POST /api/v1/auth/oauth/token`: 20 req/min per IP (code exchange)

Add these rules alongside the existing login rate limit check. The exact code depends on the current filter structure — follow the existing pattern for `POST /api/v1/auth/login`.

- [ ] **Step 2: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/config/RateLimitFilter.java
git commit -m "feat(api): add rate limiting for OAuth endpoints"
```

---

### Task 11: End-to-end verification

- [ ] **Step 1: Build backend**

Run: `cd lakeon-api && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Type check frontend**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit`
Expected: No errors

- [ ] **Step 3: Verify Flyway migration naming**

Check that V34 is the correct next number (no gaps or conflicts with any concurrent work).

- [ ] **Step 4: Manual testing checklist**

With Google/GitHub OAuth apps created and env vars configured:
1. Visit `/login` — verify Google and GitHub buttons appear
2. Click "Google 登录" — redirected to Google consent screen
3. Authorize — redirected back to `/oauth/callback?code=xxx`
4. Callback page shows "正在完成登录..." then redirects to `/dashboard`
5. User is logged in with correct name displayed
6. Repeat for GitHub
7. Log out, log in again with same OAuth provider — same tenant found
8. Check DB: `oauth_connections` has entries, `tenants` has email populated
