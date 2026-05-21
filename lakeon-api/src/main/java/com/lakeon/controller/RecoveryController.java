package com.lakeon.controller;

import com.lakeon.model.dto.PitrRequest;
import com.lakeon.model.dto.PitrResponse;
import com.lakeon.model.dto.PitrWindow;
import com.lakeon.service.RecoveryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for database recovery operations.
 *
 * <p>Provides Point-In-Time Restore (PITR) which branches a database's Neon timeline
 * at the LSN corresponding to a target time, registering a new database entity
 * that points at the branched timeline ("new-branch" semantics — source is untouched).
 *
 * <p>{@link com.lakeon.service.exception.NotFoundException} is mapped to HTTP 404
 * by {@link GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/databases")
public class RecoveryController {

    private final RecoveryService recoveryService;

    public RecoveryController(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @PostMapping("/{dbId}/pitr")
    public ResponseEntity<PitrResponse> pitr(@PathVariable String dbId,
                                             @Valid @RequestBody PitrRequest request) {
        return ResponseEntity.ok(recoveryService.pitr(dbId, request));
    }

    @GetMapping("/{dbId}/pitr-window")
    public ResponseEntity<PitrWindow> pitrWindow(@PathVariable String dbId) {
        return ResponseEntity.ok(recoveryService.getPitrWindow(dbId));
    }
}
