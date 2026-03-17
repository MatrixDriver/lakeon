package com.lakeon.controller;

import com.lakeon.model.dto.BackupResponse;
import com.lakeon.model.dto.CreateBackupRequest;
import com.lakeon.model.dto.RestoreFromBackupRequest;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.BackupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class BackupController {
    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping("/backups")
    public List<BackupResponse> listAllBackups(HttpServletRequest req) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return backupService.listAllBackups(tenant);
    }

    @PostMapping("/databases/{dbId}/backups")
    @ResponseStatus(HttpStatus.CREATED)
    public BackupResponse createBackup(HttpServletRequest req,
                                       @PathVariable String dbId,
                                       @RequestBody CreateBackupRequest request) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return backupService.createBackup(tenant, dbId, request);
    }

    @GetMapping("/databases/{dbId}/backups")
    public List<BackupResponse> listBackups(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return backupService.listBackups(tenant, dbId);
    }

    @GetMapping("/databases/{dbId}/backups/{backupId}")
    public BackupResponse getBackup(HttpServletRequest req,
                                    @PathVariable String dbId,
                                    @PathVariable String backupId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return backupService.getBackup(tenant, dbId, backupId);
    }

    @PostMapping("/databases/{dbId}/backups/{backupId}/restore")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> restoreFromBackup(HttpServletRequest req,
                                                  @PathVariable String dbId,
                                                  @PathVariable String backupId,
                                                  @Valid @RequestBody RestoreFromBackupRequest request) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        DatabaseEntity restoredDb = backupService.restoreFromBackup(tenant, dbId, backupId, request);
        return Map.of(
            "id", restoredDb.getId(),
            "name", restoredDb.getName(),
            "status", restoredDb.getStatus().name()
        );
    }

    @DeleteMapping("/databases/{dbId}/backups/{backupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBackup(HttpServletRequest req,
                             @PathVariable String dbId,
                             @PathVariable String backupId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        backupService.deleteBackup(tenant, dbId, backupId);
    }
}
