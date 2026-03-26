package com.lakeon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lakeon.model.entity.TenantEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

@Component
public class McpToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);
    private final ObjectMapper mapper;
    private final Map<String, ToolEntry> tools = new LinkedHashMap<>();

    public McpToolRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void register(String name, String description, JsonNode inputSchema,
                         BiFunction<TenantEntity, JsonNode, Object> handler) {
        tools.put(name, new ToolEntry(name, description, inputSchema, handler));
        log.info("MCP tool registered: {}", name);
    }

    public ObjectNode listTools() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode toolsArray = result.putArray("tools");
        for (ToolEntry entry : tools.values()) {
            ObjectNode tool = toolsArray.addObject();
            tool.put("name", entry.name);
            tool.put("description", entry.description);
            tool.set("inputSchema", entry.inputSchema);
        }
        return result;
    }

    public ObjectNode callTool(TenantEntity tenant, JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");

        ToolEntry entry = tools.get(name);
        if (entry == null) {
            return errorResult("Unknown tool: " + name);
        }

        try {
            Object result = entry.handler.apply(tenant, arguments);
            String text = result instanceof String ? (String) result : mapper.writeValueAsString(result);
            return successResult(text);
        } catch (Exception e) {
            log.error("MCP tool '{}' failed: {}", name, e.getMessage(), e);
            return errorResult("Error: " + e.getMessage());
        }
    }

    private ObjectNode successResult(String text) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "text");
        item.put("text", text);
        return result;
    }

    private ObjectNode errorResult(String message) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "text");
        item.put("text", message);
        result.put("isError", true);
        return result;
    }

    private record ToolEntry(String name, String description, JsonNode inputSchema,
                             BiFunction<TenantEntity, JsonNode, Object> handler) {}
}
