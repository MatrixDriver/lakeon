package com.lakeon.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Rate limiting filter using in-memory sliding window counters.
 * Runs before ApiKeyFilter to block abusive traffic early.
 *
 * Rules:
 *   POST /api/v1/tenants    — 10 req/hour per IP  (registration)
 *   POST /api/v1/auth/login — 10 req/min  per IP  (login)
 *   Other authenticated API — 200 req/min per API Key
 */
@Component
@Order(0)
public class RateLimitFilter implements Filter {

    private final ConcurrentHashMap<String, SlidingWindow> windows = new ConcurrentHashMap<>();
    private static final long CLEANUP_INTERVAL_MS = 60_000;
    private volatile long lastCleanup = System.currentTimeMillis();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String method = req.getMethod();
        String path = req.getRequestURI();

        // Determine rate limit rule
        RateRule rule = resolveRule(method, path);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = rule.keyPrefix + ":" + resolveKey(req, rule);
        SlidingWindow window = windows.computeIfAbsent(key, k -> new SlidingWindow());
        long now = System.currentTimeMillis();

        // Periodic cleanup of expired entries
        if (now - lastCleanup > CLEANUP_INTERVAL_MS) {
            lastCleanup = now;
            cleanupExpired(now);
        }

        int count = window.countAndAdd(now, rule.windowMs);
        int remaining = Math.max(0, rule.maxRequests - count);
        long resetAt = now + rule.windowMs;

        res.setHeader("X-RateLimit-Limit", String.valueOf(rule.maxRequests));
        res.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        res.setHeader("X-RateLimit-Reset", String.valueOf(resetAt / 1000));

        if (count > rule.maxRequests) {
            res.setStatus(429);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests. Try again later.\"}}");
            return;
        }

        chain.doFilter(request, response);
    }

    private RateRule resolveRule(String method, String path) {
        if ("POST".equals(method) && path.startsWith("/api/v1/tenants")) {
            return RateRule.REGISTER;
        }
        if ("POST".equals(method) && path.startsWith("/api/v1/auth/login")) {
            return RateRule.LOGIN;
        }
        if (path.startsWith("/api/v1/") && !path.startsWith("/api/v1/admin/")
                && !path.startsWith("/actuator")) {
            return RateRule.API;
        }
        return null; // no rate limit for admin, actuator, proxy endpoints
    }

    private String resolveKey(HttpServletRequest req, RateRule rule) {
        if (rule.byIp) {
            return getClientIp(req);
        }
        // By API key
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.length() > 7) {
            return auth.substring(7); // strip "Bearer "
        }
        return getClientIp(req); // fallback to IP if no key
    }

    private String getClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    private void cleanupExpired(long now) {
        windows.entrySet().removeIf(e -> e.getValue().isExpired(now));
    }

    private enum RateRule {
        REGISTER("reg", true, 10, 3600_000L),       // 10/hour per IP
        LOGIN("login", true, 10, 60_000L),           // 10/min per IP
        API("api", false, 200, 60_000L);             // 200/min per key

        final String keyPrefix;
        final boolean byIp;
        final int maxRequests;
        final long windowMs;

        RateRule(String keyPrefix, boolean byIp, int maxRequests, long windowMs) {
            this.keyPrefix = keyPrefix;
            this.byIp = byIp;
            this.maxRequests = maxRequests;
            this.windowMs = windowMs;
        }
    }

    /**
     * Sliding window: keeps timestamps of recent requests, evicts expired ones.
     */
    private static class SlidingWindow {
        private final CopyOnWriteArrayList<Long> timestamps = new CopyOnWriteArrayList<>();

        int countAndAdd(long now, long windowMs) {
            long cutoff = now - windowMs;
            timestamps.removeIf(t -> t < cutoff);
            timestamps.add(now);
            return timestamps.size();
        }

        boolean isExpired(long now) {
            if (timestamps.isEmpty()) return true;
            // Consider expired if newest entry is older than 1 hour
            Long last = timestamps.get(timestamps.size() - 1);
            return now - last > 3600_000L;
        }
    }
}
