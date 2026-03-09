package com.lakeon.controller;

import com.lakeon.model.dto.CreateTenantRequest;
import com.lakeon.model.dto.LoginRequest;
import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.dto.TenantUsageSummary;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.TenantService;
import com.lakeon.service.UsageMeteringService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class TenantController {
    private final TenantService tenantService;
    private final UsageMeteringService usageMeteringService;

    public TenantController(TenantService tenantService, UsageMeteringService usageMeteringService) {
        this.tenantService = tenantService;
        this.usageMeteringService = usageMeteringService;
    }

    @PostMapping("/auth/login")
    public TenantResponse login(@Valid @RequestBody LoginRequest request) {
        TenantResponse resp = tenantService.login(request);
        if (resp == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        return resp;
    }

    @GetMapping("/auth/check-username")
    public Map<String, Boolean> checkUsername(@RequestParam String username) {
        boolean available = tenantService.isUsernameAvailable(username);
        return Map.of("available", available);
    }

    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse createTenant(@Valid @RequestBody CreateTenantRequest request) {
        return tenantService.create(request);
    }

    @GetMapping("/tenants/me")
    public TenantResponse getCurrentTenant(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return tenantService.get(tenant.getId());
    }

    @GetMapping("/tenants/{tenantId}")
    public TenantResponse getTenant(@PathVariable String tenantId) {
        return tenantService.get(tenantId);
    }

    @PostMapping("/tenants/{tenantId}/regenerate-key")
    public TenantResponse regenerateKey(HttpServletRequest req, @PathVariable String tenantId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        if (!tenant.getId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot regenerate key for another tenant");
        }
        return tenantService.regenerateApiKey(tenantId);
    }

    // ── Multi API Key Management ──

    @GetMapping("/api-keys")
    public List<Map<String, Object>> listApiKeys(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return tenantService.listApiKeys(tenant.getId());
    }

    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createApiKey(HttpServletRequest req, @RequestBody Map<String, String> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return tenantService.createApiKey(tenant.getId(), body.get("name"));
    }

    @DeleteMapping("/api-keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteApiKey(HttpServletRequest req, @PathVariable String keyId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        tenantService.deleteApiKey(tenant.getId(), keyId);
    }

    // ── Usage (tenant-facing) ──

    @GetMapping("/usage/me")
    public TenantUsageSummary getMyUsage(HttpServletRequest req,
                                         @RequestParam(required = false, name = "bill_cycle") String billCycle) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        Instant from;
        Instant to;
        if (billCycle != null && !billCycle.isBlank()) {
            YearMonth ym = YearMonth.parse(billCycle);
            from = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            to = ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        } else {
            YearMonth current = YearMonth.now(ZoneOffset.UTC);
            from = current.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            to = Instant.now();
        }
        return usageMeteringService.getTenantUsage(tenant.getId(), from, to);
    }

    // ── Account Settings ──

    @PostMapping("/account/change-password")
    public Map<String, String> changePassword(HttpServletRequest req, @RequestBody Map<String, String> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        tenantService.changePassword(tenant.getId(), body.get("current_password"), body.get("new_password"));
        return Map.of("message", "密码修改成功");
    }

    @PatchMapping("/account/profile")
    public TenantResponse updateProfile(HttpServletRequest req, @RequestBody Map<String, String> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return tenantService.updateProfile(tenant.getId(), body.get("name"));
    }
}
