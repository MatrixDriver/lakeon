package com.lakeon.agentfs;

import com.lakeon.memory.MemoryService;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public API for tenants to inspect and configure which memory base
 * their AgentFS directives should target.
 *
 * <p>Used by the Console UI in Phase D. Tenant is resolved via the
 * {@code "tenant"} request attribute, matching {@link AgentFSController}.
 */
@RestController
@RequestMapping("/api/v1/agentfs/memory-target")
public class AgentFSMemoryTargetController {

    private static final Logger log = LoggerFactory.getLogger(AgentFSMemoryTargetController.class);

    private final AgentFSMemoryTargetRepository repo;
    private final MemoryService memoryService;
    private final AgentFSDatabaseManager dbm;
    private final AgentFSAssignmentRepository agentfsAssignmentRepo;

    public AgentFSMemoryTargetController(AgentFSMemoryTargetRepository repo,
                                         MemoryService memoryService,
                                         AgentFSDatabaseManager dbm,
                                         AgentFSAssignmentRepository agentfsAssignmentRepo) {
        this.repo = repo;
        this.memoryService = memoryService;
        this.dbm = dbm;
        this.agentfsAssignmentRepo = agentfsAssignmentRepo;
    }

    @GetMapping
    public Map<String, Object> get(HttpServletRequest req) {
        TenantEntity t = resolveTenant(req);
        Map<String, Object> out = new LinkedHashMap<>();
        repo.findByTenantId(t.getId()).ifPresentOrElse(
            e -> {
                out.put("base_id", e.getMemoryBaseId());
                out.put("auto_created", e.getAutoCreated());
                out.put("updated_at", e.getUpdatedAt().toString());
            },
            () -> {
                out.put("base_id", null);
                out.put("auto_created", false);
            }
        );
        return out;
    }

    @PostMapping
    public Map<String, Object> set(HttpServletRequest req,
                                   @RequestBody Map<String, String> body) {
        TenantEntity t = resolveTenant(req);
        String baseId = body == null ? null : body.get("base_id");
        if (baseId == null || baseId.isBlank()) {
            throw new BadRequestException("base_id required");
        }
        // Verify the base exists and belongs to this tenant.
        // MemoryService#getBase throws NotFoundException if the tenant does
        // not own a base with this id, which maps to 404 automatically.
        memoryService.getBase(t.getId(), baseId);

        AgentFSMemoryTargetEntity e = repo.findByTenantId(t.getId())
            .orElseGet(() -> {
                AgentFSMemoryTargetEntity n = new AgentFSMemoryTargetEntity();
                n.setTenantId(t.getId());
                return n;
            });
        e.setMemoryBaseId(baseId);
        e.setAutoCreated(false);
        e.setUpdatedAt(Instant.now());
        repo.save(e);

        return Map.of("base_id", baseId, "auto_created", false);
    }

    @GetMapping("/pending-derivation-count")
    public Map<String, Object> pendingDerivationCount(HttpServletRequest req) {
        TenantEntity t = resolveTenant(req);
        // If AgentFS DB not provisioned yet, return 0 quietly
        if (agentfsAssignmentRepo.findByTenantId(t.getId()).isEmpty()) {
            return Map.of("count", 0L);
        }
        try (Connection c = dbm.openConnection(t);
             PreparedStatement st = c.prepareStatement(
                 "SELECT count(*) FROM agentfs_events WHERE status='pending'");
             ResultSet rs = st.executeQuery()) {
            rs.next();
            return Map.of("count", rs.getLong(1));
        } catch (SQLException e) {
            log.warn("pending-derivation-count query failed for tenant {}: {}", t.getId(), e.getMessage());
            return Map.of("count", 0L);
        }
    }

    private TenantEntity resolveTenant(HttpServletRequest req) {
        TenantEntity t = (TenantEntity) req.getAttribute("tenant");
        if (t == null) throw new BadRequestException("no authenticated tenant");
        return t;
    }
}
