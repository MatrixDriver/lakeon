package com.lakeon.config;

import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.TenantRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;

/**
 * Trial demo filter: for trial users, redirect reads to demo tenant, block writes.
 * Runs after ApiKeyFilter (@Order(1)) which sets the "tenant" request attribute.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "lakeon.trial-demo.enabled", havingValue = "true", matchIfMissing = true)
public class TrialDemoFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(TrialDemoFilter.class);

    private final LakeonProperties props;
    private final TenantRepository tenantRepository;

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    // POST endpoints that are read-like or operational (search, query, recall, resume/suspend)
    private static final Set<String> READ_POST_PATHS = Set.of(
        "/query",
        "/search",
        "/recall",
        "/resume",
        "/suspend"
    );

    // Path prefixes where trial users can do writes (quota-enforced by the service layer)
    private static final Set<String> TRIAL_WRITE_PATHS = Set.of(
        "/api/v1/datalake/"
    );

    // Paths that should always use the trial user's own tenant (not demo)
    private static final Set<String> OWN_TENANT_PATHS = Set.of(
        "/api/v1/tenants/me",
        "/api/v1/usage",
        "/api/v1/auth/"
    );

    public TrialDemoFilter(LakeonProperties props, TenantRepository tenantRepository) {
        this.props = props;
        this.tenantRepository = tenantRepository;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        TenantEntity tenant = (TenantEntity) request.getAttribute("tenant");
        if (tenant == null || !Boolean.TRUE.equals(tenant.getTrial())) {
            chain.doFilter(req, res);
            return;
        }

        String demoTenantId = props.getDemo().getTenantId();
        if (demoTenantId == null || demoTenantId.isBlank()) {
            chain.doFilter(req, res);
            return;
        }

        if (tenant.getExpiresAt() != null && Instant.now().isAfter(tenant.getExpiresAt())) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"error\":{\"code\":\"TRIAL_EXPIRED\",\"message\":\"体验账号已过期，请注册账号继续使用\"}}"
            );
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();

        for (String ownPath : OWN_TENANT_PATHS) {
            if (path.startsWith(ownPath)) {
                chain.doFilter(req, res);
                return;
            }
        }

        boolean isRead = READ_METHODS.contains(method);
        if ("POST".equals(method)) {
            for (String readPostPath : READ_POST_PATHS) {
                if (path.endsWith(readPostPath)) {
                    isRead = true;
                    break;
                }
            }
        }

        if (!isRead) {
            // Allow writes to specific paths (quota-enforced by service layer)
            for (String writePath : TRIAL_WRITE_PATHS) {
                if (path.startsWith(writePath)) {
                    chain.doFilter(req, res);
                    return;
                }
            }
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"error\":{\"code\":\"TRIAL_RESTRICTED\",\"message\":\"体验模式为只读，注册账号后可使用全部功能\"}}"
            );
            return;
        }

        TenantEntity demoTenant = tenantRepository.findById(demoTenantId).orElse(null);
        if (demoTenant == null) {
            log.warn("Demo tenant {} not found, falling back to trial tenant", demoTenantId);
            chain.doFilter(req, res);
            return;
        }

        request.setAttribute("tenant", demoTenant);
        chain.doFilter(req, res);
    }
}
