package com.lakeon.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;

/**
 * Keeps split API deployments from accidentally serving the other role's routes.
 * The legacy monolith deployment uses role "all" and remains fully compatible.
 */
@Component
@Order(-10)
public class ApiRoleFilter implements Filter {
    private final Environment environment;

    public ApiRoleFilter(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String role = apiRole();
        String path = request.getRequestURI();

        if (isSharedPath(path) || "all".equals(role)) {
            chain.doFilter(req, res);
            return;
        }

        if ("admin".equals(role)) {
            if (isAdminPath(path)) {
                chain.doFilter(req, res);
                return;
            }
            rejectNotServedHere(response, role);
            return;
        }

        if ("serving".equals(role)) {
            if (!isAdminPath(path)) {
                chain.doFilter(req, res);
                return;
            }
            rejectNotServedHere(response, role);
            return;
        }

        response.setStatus(503);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":{\"code\":\"MISCONFIGURED\",\"message\":\"Invalid LAKEON_API_ROLE\"}}");
    }

    private String apiRole() {
        String role = environment.getProperty("LAKEON_API_ROLE");
        if (role == null || role.isBlank()) {
            role = environment.getProperty("lakeon.api.role", "all");
        }
        return role == null ? "all" : role.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isSharedPath(String path) {
        return path.startsWith("/actuator") || path.equals("/error") || path.equals("/favicon.ico");
    }

    private boolean isAdminPath(String path) {
        return path.equals("/api/v1/admin") || path.startsWith("/api/v1/admin/");
    }

    private void rejectNotServedHere(HttpServletResponse response, String role) throws IOException {
        response.setStatus(404);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"Endpoint is not served by API role " + role + "\"}}");
    }
}
