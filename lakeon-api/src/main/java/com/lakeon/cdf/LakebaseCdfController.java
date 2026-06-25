package com.lakeon.cdf;

import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/databases/{databaseId}/cdf-streams")
public class LakebaseCdfController {

    private final LakebaseCdfService service;

    public LakebaseCdfController(LakebaseCdfService service) {
        this.service = service;
    }

    @PostMapping
    public CdfStreamResponse create(HttpServletRequest servletRequest,
                                    @PathVariable String databaseId,
                                    @RequestBody CreateCdfStreamRequest request) {
        return service.create(getTenant(servletRequest), databaseId, request);
    }

    @GetMapping
    public List<CdfStreamResponse> list(HttpServletRequest servletRequest,
                                        @PathVariable String databaseId) {
        return service.list(getTenant(servletRequest), databaseId);
    }

    @PostMapping("/{streamId}/resume")
    public CdfStreamResponse resume(HttpServletRequest servletRequest,
                                    @PathVariable String databaseId,
                                    @PathVariable String streamId) {
        return service.resume(getTenant(servletRequest), databaseId, streamId);
    }

    @PostMapping("/{streamId}/pause")
    public CdfStreamResponse pause(HttpServletRequest servletRequest,
                                   @PathVariable String databaseId,
                                   @PathVariable String streamId) {
        return service.pause(getTenant(servletRequest), databaseId, streamId);
    }

    @PostMapping("/{streamId}/export")
    public ExportResponse export(HttpServletRequest servletRequest,
                                 @PathVariable String databaseId,
                                 @PathVariable String streamId) {
        return service.export(getTenant(servletRequest), databaseId, streamId);
    }

    @GetMapping("/{streamId}/export")
    public ExportResponse getExport(HttpServletRequest servletRequest,
                                    @PathVariable String databaseId,
                                    @PathVariable String streamId) {
        return service.getExport(getTenant(servletRequest), databaseId, streamId);
    }

    private static TenantEntity getTenant(HttpServletRequest request) {
        TenantEntity tenant = (TenantEntity) request.getAttribute("tenant");
        if (tenant == null) {
            throw new BadRequestException("no authenticated tenant");
        }
        return tenant;
    }

    public record CreateCdfStreamRequest(
            String database_id,
            String branch_id,
            String source_schema,
            String source_table,
            String target_namespace,
            String target_table,
            String mode,
            Boolean initial_backfill) {
    }

    public record CdfStreamResponse(
            String id,
            String database_id,
            String branch_id,
            String source_schema,
            String source_table,
            String target_namespace,
            String target_table,
            String mode,
            String status,
            String backfill_status,
            String backfill_lsn,
            String slot_name,
            String publication_name,
            String export_status,
            boolean readable) {
    }

    public record ExportResponse(String status, String metadata_location) {
    }
}
