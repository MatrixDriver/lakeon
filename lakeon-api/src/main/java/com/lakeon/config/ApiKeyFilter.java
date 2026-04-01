package com.lakeon.config;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.TenantService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * API Key authentication filter.
 *
 * Extracts API Key from Authorization: Bearer <api-key> header.
 * After validation, stores the Tenant in the request attribute.
 *
 * /proxy/** endpoints require internal token (--control-plane-token).
 * /actuator/** endpoints are excluded from auth.
 * POST /api/v1/tenants is excluded from auth (create tenant).
 */
@Component
@Order(1)
public class ApiKeyFilter implements Filter {
    private final TenantService tenantService;
    private final LakeonProperties props;

    public ApiKeyFilter(TenantService tenantService, LakeonProperties props) {
        this.tenantService = tenantService;
        this.props = props;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();

        // CORS headers for cross-origin requests (e.g. Railway frontend -> CCE API)
        String origin = request.getHeader("Origin");
        if (origin != null) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Access-Control-Max-Age", "3600");
        }

        // Preflight requests
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(200);
            return;
        }

        // Exclude actuator endpoints
        if (path.startsWith("/actuator")) {
            chain.doFilter(req, res);
            return;
        }

        // Proxy adapter endpoints require internal token
        if (path.startsWith("/proxy/")) {
            String internalToken = props.getProxy().getInternalToken();
            if (internalToken != null && !internalToken.isBlank()) {
                String authHeader = request.getHeader("Authorization");
                if (authHeader == null || !authHeader.equals("Bearer " + internalToken)) {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"Invalid internal token\"}}");
                    return;
                }
            }
            chain.doFilter(req, res);
            return;
        }

        // Admin API endpoints require admin token
        if (path.startsWith("/api/v1/admin/")) {
            String adminToken = props.getAdmin().getToken();
            if (adminToken == null || adminToken.isBlank()) {
                response.setStatus(403);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"Admin API is not configured\"}}");
                return;
            }
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.equals("Bearer " + adminToken)) {
                response.setStatus(403);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"Invalid admin token\"}}");
                return;
            }
            chain.doFilter(req, res);
            return;
        }

        // Auth endpoints (login/register) don't need auth
        if ("POST".equals(request.getMethod()) && "/api/v1/tenants".equals(path)) {
            chain.doFilter(req, res);
            return;
        }
        if ("POST".equals(request.getMethod()) && "/api/v1/auth/login".equals(path)) {
            chain.doFilter(req, res);
            return;
        }
        if ("GET".equals(request.getMethod()) && "/api/v1/auth/check-username".equals(path)) {
            chain.doFilter(req, res);
            return;
        }

        // Trial endpoint (no auth required)
        if ("POST".equals(request.getMethod()) && "/api/v1/trial".equals(path)) {
            chain.doFilter(req, res);
            return;
        }

        // MCP descriptions (public, no auth)
        if ("GET".equals(request.getMethod()) && "/api/v1/mcp/descriptions".equals(path)) {
            chain.doFilter(req, res);
            return;
        }

        // WebSocket endpoints handle their own auth via query param
        if (path.startsWith("/ws/")) {
            chain.doFilter(req, res);
            return;
        }

        // Import callback from Job Pods (internal only)
        if (path.startsWith("/api/v1/import/callback/")) {
            chain.doFilter(req, res);
            return;
        }

        // Job callback and connstr refresh from Job Pods (token-authenticated internally)
        if (path.matches("/api/v1/jobs/[^/]+/callback") || path.matches("/api/v1/jobs/[^/]+/connstr")) {
            chain.doFilter(req, res);
            return;
        }

        // Extract API Key
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid Authorization header\"}}");
            return;
        }

        String apiKey = authHeader.substring(7);
        TenantEntity tenant = tenantService.authenticateByApiKey(apiKey);
        if (tenant == null) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid API key\"}}");
            return;
        }

        if (Boolean.TRUE.equals(tenant.getDisabled())) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"Tenant is disabled\"}}");
            return;
        }

        request.setAttribute("tenant", tenant);
        org.slf4j.MDC.put("tenantId", tenant.getId());
        chain.doFilter(req, res);
    }
}
