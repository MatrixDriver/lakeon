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
     */
    @GetMapping("/{provider}/callback")
    public void callback(@PathVariable String provider,
                         @RequestParam String code,
                         @RequestParam(required = false) String state,
                         HttpServletResponse response) throws IOException {
        try {
            String tenantId = switch (provider) {
                case "google" -> oauthService.handleGoogleCallback(code);
                case "github" -> oauthService.handleGithubCallback(code);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown provider: " + provider);
            };

            // Get tenant with api_key
            TenantResponse tenant = tenantService.getForLogin(tenantId);

            // Extract redirect_uri from state
            String redirectUri = extractRedirectUri(state);
            if (redirectUri.isEmpty()) {
                redirectUri = props.getOauth().getCallbackBaseUrl().replace("/api/v1", "")
                        .replace(":8080", ":5173");
                if (redirectUri.contains("8443")) {
                    redirectUri = "https://console.dbay.cloud";
                }
                redirectUri += "/oauth/callback";
            }

            // Pass tenant info via hash fragment (not exposed in server logs)
            String fragment = "api_key=" + tenant.getApiKey()
                    + "&tenant_id=" + tenant.getId()
                    + "&name=" + java.net.URLEncoder.encode(tenant.getName() != null ? tenant.getName() : "", java.nio.charset.StandardCharsets.UTF_8)
                    + "&username=" + java.net.URLEncoder.encode(tenant.getUsername() != null ? tenant.getUsername() : "", java.nio.charset.StandardCharsets.UTF_8);
            response.sendRedirect(redirectUri + "#" + fragment);
        } catch (Exception e) {
            log.error("OAuth callback failed for provider={}", provider, e);
            // Redirect to frontend login page, not backend
            String errorRedirect = extractRedirectUri(state);
            if (errorRedirect.isEmpty()) {
                errorRedirect = "https://dbay.cloud/login";
            } else {
                // Strip /oauth/callback path to get base, then append /login
                errorRedirect = errorRedirect.replaceAll("/oauth/callback.*", "/login");
            }
            response.sendRedirect(errorRedirect + "?error=oauth_failed");
        }
    }

    /**
     * Step 3: Frontend exchanges temp auth code for API key.
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

        return tenantService.getForLogin(tenantId);
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
