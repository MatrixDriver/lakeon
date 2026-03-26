package com.lakeon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mcp")
public class McpController {
    private final ObjectMapper mapper;
    private final McpToolRegistry registry;

    public McpController(ObjectMapper mapper, McpToolRegistry registry) {
        this.mapper = mapper;
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<JsonNode> handle(HttpServletRequest req,
                                           @RequestBody JsonNode body) {
        String method = body.path("method").asText("");
        JsonNode id = body.get("id");
        JsonNode params = body.path("params");

        return switch (method) {
            case "initialize" -> ok(id, initializeResult());
            case "notifications/initialized" -> ResponseEntity.accepted().build();
            case "tools/list" -> ok(id, registry.listTools());
            case "tools/call" -> {
                TenantEntity tenant = (TenantEntity) req.getAttribute("tenant");
                yield ok(id, registry.callTool(tenant, params));
            }
            default -> error(id, -32601, "Method not found: " + method);
        };
    }

    private ObjectNode initializeResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", "2025-03-26");
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "dbay");
        serverInfo.put("version", "1.0.0");
        return result;
    }

    private ResponseEntity<JsonNode> ok(JsonNode id, ObjectNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<JsonNode> error(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return ResponseEntity.ok(response);
    }
}
