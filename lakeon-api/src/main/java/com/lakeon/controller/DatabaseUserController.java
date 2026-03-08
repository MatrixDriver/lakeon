package com.lakeon.controller;

import com.lakeon.model.dto.*;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.DatabaseUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/databases/{dbId}/users")
public class DatabaseUserController {
    private final DatabaseUserService databaseUserService;

    public DatabaseUserController(DatabaseUserService databaseUserService) {
        this.databaseUserService = databaseUserService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DatabaseUserCreatedResponse createUser(HttpServletRequest req,
                                                   @PathVariable String dbId,
                                                   @Valid @RequestBody CreateDatabaseUserRequest request) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return databaseUserService.createUser(tenant, dbId, request);
    }

    @GetMapping
    public List<DatabaseUserResponse> listUsers(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return databaseUserService.listUsers(tenant, dbId);
    }

    @PutMapping("/{userId}/role")
    public DatabaseUserResponse updateUserRole(HttpServletRequest req,
                                                @PathVariable String dbId,
                                                @PathVariable String userId,
                                                @Valid @RequestBody UpdateDatabaseUserRoleRequest request) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return databaseUserService.updateUserRole(tenant, dbId, userId, request);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(HttpServletRequest req,
                           @PathVariable String dbId,
                           @PathVariable String userId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        databaseUserService.deleteUser(tenant, dbId, userId);
    }

    @PostMapping("/{userId}/reset-password")
    public Map<String, String> resetPassword(HttpServletRequest req,
                                              @PathVariable String dbId,
                                              @PathVariable String userId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        DatabaseUserCreatedResponse response = databaseUserService.resetPassword(tenant, dbId, userId);
        return Map.of("password", response.password());
    }
}
