package com.lakeon.controller;

import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.ExtensionService;
import com.lakeon.service.ExtensionService.ExtensionInfo;
import com.lakeon.service.ExtensionService.ParameterInfo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/databases/{dbId}")
public class ExtensionController {

    private final ExtensionService extensionService;

    public ExtensionController(ExtensionService extensionService) {
        this.extensionService = extensionService;
    }

    @GetMapping("/extensions")
    public List<ExtensionInfo> listExtensions(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return extensionService.listExtensions(tenant, dbId);
    }

    @PostMapping("/extensions/{name}/enable")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> enableExtension(HttpServletRequest req,
                                               @PathVariable String dbId,
                                               @PathVariable String name) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        extensionService.enableExtension(tenant, dbId, name);
        return Map.of("status", "enabled", "extension", name);
    }

    @PostMapping("/extensions/{name}/disable")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> disableExtension(HttpServletRequest req,
                                                @PathVariable String dbId,
                                                @PathVariable String name) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        extensionService.disableExtension(tenant, dbId, name);
        return Map.of("status", "disabled", "extension", name);
    }

    @GetMapping("/parameters")
    public List<ParameterInfo> listParameters(HttpServletRequest req, @PathVariable String dbId) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        return extensionService.listParameters(tenant, dbId);
    }

    @PutMapping("/parameters/{name}")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> updateParameter(HttpServletRequest req,
                                               @PathVariable String dbId,
                                               @PathVariable String name,
                                               @RequestBody Map<String, String> body) {
        TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
        String value = body.get("value");
        extensionService.updateParameter(tenant, dbId, name, value);
        return Map.of("status", "updated", "parameter", name, "value", value);
    }
}
