package com.lakeon.controller;

import com.lakeon.model.entity.SystemConfigEntity;
import com.lakeon.repository.SystemConfigRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public endpoint for MCP server to fetch tool descriptions.
 * No auth required — descriptions are not sensitive.
 */
@RestController
@RequestMapping("/api/v1/mcp")
public class McpDescriptionsController {

    private static final String MCP_DESCRIPTIONS_KEY = "mcp_tool_descriptions";
    private final SystemConfigRepository systemConfigRepository;

    public McpDescriptionsController(SystemConfigRepository systemConfigRepository) {
        this.systemConfigRepository = systemConfigRepository;
    }

    @GetMapping("/descriptions")
    public ResponseEntity<String> getDescriptions() {
        return systemConfigRepository.findById(MCP_DESCRIPTIONS_KEY)
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .map(e -> ResponseEntity.ok(e.getValue()))
                .orElse(ResponseEntity.noContent().build());
    }
}
