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
import java.util.Optional;
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
        LakeonProperties.OAuthProviderConfig google = props.getOauth().getGoogle();
        return "https://accounts.google.com/o/oauth2/v2/auth" +
                "?client_id=" + enc(google.getClientId()) +
                "&redirect_uri=" + enc(getCallbackUrl("google")) +
                "&response_type=code" +
                "&scope=" + enc("openid email profile") +
                "&state=" + enc(state) +
                "&access_type=offline" +
                "&prompt=select_account";
    }

    public String getGithubAuthUrl(String state) {
        LakeonProperties.OAuthProviderConfig github = props.getOauth().getGithub();
        return "https://github.com/login/oauth/authorize" +
                "?client_id=" + enc(github.getClientId()) +
                "&redirect_uri=" + enc(getCallbackUrl("github")) +
                "&scope=" + enc("user:email") +
                "&state=" + enc(state);
    }

    // ── Callback handlers ──

    @Transactional
    public String handleGoogleCallback(String code) throws Exception {
        LakeonProperties.OAuthProviderConfig google = props.getOauth().getGoogle();

        // 1. Exchange code for tokens
        String tokenBody = "code=" + enc(code) +
                "&client_id=" + enc(google.getClientId()) +
                "&client_secret=" + enc(google.getClientSecret()) +
                "&redirect_uri=" + enc(getCallbackUrl("google")) +
                "&grant_type=authorization_code";
        JsonNode tokenResp = postForm(relayUrl("/oauth-relay/google/token", "https://oauth2.googleapis.com/token"), tokenBody);
        String accessToken = tokenResp.path("access_token").asText(null);
        String refreshToken = tokenResp.path("refresh_token").asText(null);
        String scope = tokenResp.path("scope").asText(null);

        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("Google token exchange failed: " + tokenResp);
        }

        // 2. Fetch user info
        JsonNode userInfo = getJson(relayUrl("/oauth-relay/google/userinfo", "https://www.googleapis.com/oauth2/v2/userinfo"), "Bearer " + accessToken);

        // 3. Extract fields
        String providerUserId = userInfo.path("id").asText(null);
        String email = userInfo.path("email").asText(null);
        String displayName = userInfo.path("name").asText(null);
        String avatarUrl = userInfo.path("picture").asText(null);

        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalStateException("Google user info missing id");
        }

        // 4. Find or create tenant and generate temp auth code
        return findOrCreateTenant("google", providerUserId, email, displayName, avatarUrl,
                accessToken, refreshToken, scope);
    }

    @Transactional
    public String handleGithubCallback(String code) throws Exception {
        LakeonProperties.OAuthProviderConfig github = props.getOauth().getGithub();

        // 1. Exchange code for token
        // For relay: send params as query string to avoid GFW DPI on POST body
        String tokenParams = "code=" + enc(code) +
                "&client_id=" + enc(github.getClientId()) +
                "&client_secret=" + enc(github.getClientSecret()) +
                "&redirect_uri=" + enc(getCallbackUrl("github"));
        String relayBase = props.getOauth().getRelayBaseUrl();
        JsonNode tokenResp;
        if (relayBase != null && !relayBase.isBlank()) {
            // Via relay: params go as query string, nginx forwards to GitHub
            tokenResp = postForm(relayBase.replaceAll("/+$", "") + "/oauth-relay/github/token?" + tokenParams, "");
        } else {
            // Direct: standard POST body
            tokenResp = postForm("https://github.com/login/oauth/access_token", tokenParams);
        }
        String accessToken = tokenResp.path("access_token").asText(null);
        String scope = tokenResp.path("scope").asText(null);

        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("GitHub token exchange failed: " + tokenResp);
        }

        // 2. Fetch user info
        JsonNode userInfo = getJson(relayUrl("/oauth-relay/github/api/user", "https://api.github.com/user"), "Bearer " + accessToken);

        // 3. Extract fields; if email is null, fetch from /user/emails
        String providerUserId = String.valueOf(userInfo.path("id").asLong());
        String displayName = userInfo.path("name").asText(null);
        if (displayName == null || displayName.isBlank()) {
            displayName = userInfo.path("login").asText(null);
        }
        String avatarUrl = userInfo.path("avatar_url").asText(null);
        String email = userInfo.path("email").asText(null);

        if (email == null || email.isBlank()) {
            // Fetch primary verified email from /user/emails
            JsonNode emails = getJson(relayUrl("/oauth-relay/github/api/user/emails", "https://api.github.com/user/emails"), "Bearer " + accessToken);
            if (emails.isArray()) {
                for (JsonNode emailNode : emails) {
                    if (emailNode.path("primary").asBoolean(false) && emailNode.path("verified").asBoolean(false)) {
                        email = emailNode.path("email").asText(null);
                        break;
                    }
                }
                // Fallback: first verified email
                if (email == null || email.isBlank()) {
                    for (JsonNode emailNode : emails) {
                        if (emailNode.path("verified").asBoolean(false)) {
                            email = emailNode.path("email").asText(null);
                            break;
                        }
                    }
                }
            }
        }

        if (providerUserId == null || providerUserId.isBlank() || "0".equals(providerUserId)) {
            throw new IllegalStateException("GitHub user info missing id");
        }

        // 4. Find or create tenant and generate temp auth code
        return findOrCreateTenant("github", providerUserId, email, displayName, avatarUrl,
                accessToken, null, scope);
    }

    // ── Temporary auth code exchange ──

    public String exchangeAuthCode(String authCode) {
        AuthCodeEntry entry = authCodes.remove(authCode);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAt()) {
            return null;
        }
        return entry.tenantId();
    }

    // ── Internal helpers ──

    private String findOrCreateTenant(String provider, String providerUserId, String email,
                                       String displayName, String avatarUrl,
                                       String accessToken, String refreshToken, String scope) {
        TenantEntity tenant;

        // 1. Check oauth_connections for existing connection
        Optional<OAuthConnectionEntity> existingConn = oauthRepo.findByProviderAndProviderUserId(provider, providerUserId);

        if (existingConn.isPresent()) {
            OAuthConnectionEntity conn = existingConn.get();
            // Update tokens
            conn.setAccessToken(accessToken);
            conn.setRefreshToken(refreshToken);
            conn.setScope(scope);
            conn.setDisplayName(displayName);
            conn.setAvatarUrl(avatarUrl);
            conn.setEmail(email);
            oauthRepo.save(conn);

            tenant = tenantRepo.findById(conn.getTenantId())
                    .orElseThrow(() -> new IllegalStateException("Tenant not found for OAuth connection: " + conn.getTenantId()));
        } else {
            // 2. Try to match by email to existing tenant
            if (email != null && !email.isBlank()) {
                Optional<TenantEntity> byEmail = tenantRepo.findByEmail(email);
                if (byEmail.isPresent()) {
                    tenant = byEmail.get();
                    // Update avatar if not set
                    if (tenant.getAvatarUrl() == null && avatarUrl != null) {
                        tenant.setAvatarUrl(avatarUrl);
                        tenantRepo.save(tenant);
                    }
                } else {
                    // 3. Create new tenant
                    tenant = createNewTenant(provider, displayName, providerUserId, email, avatarUrl);
                }
            } else {
                // No email, always create new tenant
                tenant = createNewTenant(provider, displayName, providerUserId, email, avatarUrl);
            }

            // Create oauth_connection record
            OAuthConnectionEntity newConn = new OAuthConnectionEntity();
            newConn.setTenantId(tenant.getId());
            newConn.setProvider(provider);
            newConn.setProviderUserId(providerUserId);
            newConn.setEmail(email);
            newConn.setDisplayName(displayName);
            newConn.setAvatarUrl(avatarUrl);
            newConn.setAccessToken(accessToken);
            newConn.setRefreshToken(refreshToken);
            newConn.setScope(scope);
            oauthRepo.save(newConn);
        }

        // 4. Check if tenant is disabled
        if (Boolean.TRUE.equals(tenant.getDisabled())) {
            throw new IllegalStateException("Account is disabled");
        }

        log.info("OAuth login: provider={} tenantId={}", provider, tenant.getId());
        return tenant.getId();
    }

    private TenantEntity createNewTenant(String provider, String displayName, String providerUserId,
                                          String email, String avatarUrl) {
        String username = generateUniqueUsername(provider, displayName, providerUserId);
        TenantEntity tenant = new TenantEntity();
        tenant.setName(username);
        tenant.setUsername(username);
        // No password for OAuth-only tenants
        tenant.setEmail(email);
        tenant.setAvatarUrl(avatarUrl);
        tenant = tenantRepo.save(tenant);

        // Create a default API key for the new tenant
        ApiKeyEntity apiKey = new ApiKeyEntity();
        apiKey.setTenantId(tenant.getId());
        apiKey.setName("Default");
        apiKeyRepo.save(apiKey);

        return tenant;
    }

    private String generateUniqueUsername(String provider, String displayName, String providerUserId) {
        // Sanitize displayName -> lowercase alphanumeric
        String base = "";
        if (displayName != null && !displayName.isBlank()) {
            base = displayName.toLowerCase().replaceAll("[^a-z0-9]", "");
        }
        if (base.isBlank()) {
            base = provider + providerUserId.replaceAll("[^a-z0-9]", "");
        }
        if (base.length() > 20) {
            base = base.substring(0, 20);
        }
        if (base.isBlank()) {
            base = "user";
        }

        // Check uniqueness and add suffix if needed
        String candidate = base;
        int suffix = 1;
        while (tenantRepo.findByUsername(candidate).isPresent()) {
            candidate = base + "_" + suffix;
            suffix++;
        }
        return candidate;
    }

    private String generateAuthCode() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String relayUrl(String relayPath, String directUrl) {
        String relay = props.getOauth().getRelayBaseUrl();
        if (relay != null && !relay.isBlank()) {
            return relay.replaceAll("/+$", "") + relayPath;
        }
        return directUrl;
    }

    private String getCallbackUrl(String provider) {
        return props.getOauth().getCallbackBaseUrl() + "/api/v1/auth/oauth/" + provider + "/callback";
    }

    private JsonNode postForm(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url + ": " + responseBody);
        }
        return objectMapper.readTree(responseBody);
    }

    private JsonNode getJson(String url, String auth) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", auth)
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url + ": " + responseBody);
        }
        return objectMapper.readTree(responseBody);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private record AuthCodeEntry(String tenantId, long expiresAt) {}
}
