package com.lakeon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lakeon.memory.MemoryService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MemoryMcpTools {
    private final McpToolRegistry registry;
    private final MemoryService memoryService;
    private final ObjectMapper mapper;

    public MemoryMcpTools(McpToolRegistry registry, MemoryService memoryService, ObjectMapper mapper) {
        this.registry = registry;
        this.memoryService = memoryService;
        this.mapper = mapper;
    }

    @PostConstruct
    void register() {
        registry.register("memory_recall",
                "Search memories by semantic similarity. Use this to recall cross-project knowledge, user preferences, credentials, or past decisions.",
                schema(b -> {
                    b.prop("base_id", "string", "Memory base ID", true);
                    b.prop("query", "string", "Search query", true);
                    b.prop("memory_types", "array", "Filter by memory types: fact, episode, procedural, decision, rejection, convention", false);
                    b.prop("top_k", "integer", "Number of results to return", false);
                }),
                (tenant, args) -> {
                    String baseId = args.get("base_id").asText();
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("query", args.get("query").asText());
                    if (args.has("top_k")) body.put("top_k", args.get("top_k").asInt());
                    if (args.has("memory_types")) {
                        List<String> types = new ArrayList<>();
                        args.get("memory_types").forEach(n -> types.add(n.asText()));
                        body.put("memory_types", types);
                    }
                    return memoryService.proxyPost(tenant.getId(), baseId, "/recall", body);
                });

        registry.register("memory_ingest",
                "Store a single memory from conversation content. Use this to remember important facts, decisions, or preferences mentioned by the user.",
                schema(b -> {
                    b.prop("base_id", "string", "Memory base ID", true);
                    b.prop("content", "string", "Memory content to store", true);
                    b.prop("role", "string", "Role of the speaker (e.g. user, assistant)", false);
                    b.prop("memory_type", "string", "Type: fact, episode, procedural, decision, rejection, convention", false);
                    b.prop("importance", "number", "Importance score 0.0-1.0", false);
                }),
                (tenant, args) -> {
                    String baseId = args.get("base_id").asText();
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("content", args.get("content").asText());
                    if (args.has("role")) body.put("role", args.get("role").asText());
                    if (args.has("memory_type")) body.put("memory_type", args.get("memory_type").asText());
                    if (args.has("importance")) body.put("importance", args.get("importance").asDouble());
                    return memoryService.proxyPost(tenant.getId(), baseId, "/ingest", body);
                });

        registry.register("memory_ingest_extracted",
                "Store pre-extracted memories. Use agent-extract mode: you decide what's worth remembering and categorize it before sending.",
                schema(b -> {
                    b.prop("base_id", "string", "Memory base ID", true);
                    b.propArray("memories", "Array of memories, each with content (string), memory_type (string), and optional importance (number)", true);
                }),
                (tenant, args) -> {
                    String baseId = args.get("base_id").asText();
                    Map<String, Object> body = new LinkedHashMap<>();
                    List<Map<String, Object>> memories = new ArrayList<>();
                    args.get("memories").forEach(node -> {
                        Map<String, Object> mem = new LinkedHashMap<>();
                        mem.put("content", node.get("content").asText());
                        mem.put("memory_type", node.get("memory_type").asText());
                        if (node.has("importance")) mem.put("importance", node.get("importance").asDouble());
                        memories.add(mem);
                    });
                    body.put("memories", memories);
                    return memoryService.proxyPost(tenant.getId(), baseId, "/ingest_extracted", body);
                });

        registry.register("memory_list",
                "List memories in a memory base with optional filtering. Use this to browse stored memories or check what has been remembered.",
                schema(b -> {
                    b.prop("base_id", "string", "Memory base ID", true);
                    b.prop("memory_type", "string", "Filter by type: fact, episode, procedural, decision, rejection, convention", false);
                    b.prop("limit", "integer", "Max results to return", false);
                    b.prop("offset", "integer", "Offset for pagination", false);
                }),
                (tenant, args) -> {
                    String baseId = args.get("base_id").asText();
                    Map<String, String> params = new LinkedHashMap<>();
                    if (args.has("memory_type")) params.put("memory_type", args.get("memory_type").asText());
                    if (args.has("limit")) params.put("limit", String.valueOf(args.get("limit").asInt()));
                    if (args.has("offset")) params.put("offset", String.valueOf(args.get("offset").asInt()));
                    return memoryService.proxyGet(tenant.getId(), baseId, "/memories", params);
                });

        registry.register("memory_delete",
                "Delete a specific memory by ID. Use this to remove outdated or incorrect memories.",
                schema(b -> {
                    b.prop("base_id", "string", "Memory base ID", true);
                    b.prop("memory_id", "string", "Memory ID to delete", true);
                }),
                (tenant, args) -> {
                    String baseId = args.get("base_id").asText();
                    String memoryId = args.get("memory_id").asText();
                    return memoryService.proxyDelete(tenant.getId(), baseId, "/memories/" + memoryId);
                });
    }

    private JsonNode schema(java.util.function.Consumer<SchemaBuilder> config) {
        SchemaBuilder b = new SchemaBuilder(mapper);
        config.accept(b);
        return b.build();
    }

    private static class SchemaBuilder {
        private final ObjectMapper mapper;
        private final ObjectNode properties;
        private final ArrayNode required;

        SchemaBuilder(ObjectMapper mapper) {
            this.mapper = mapper;
            this.properties = mapper.createObjectNode();
            this.required = mapper.createArrayNode();
        }

        void prop(String name, String type, String description, boolean isRequired) {
            ObjectNode prop = properties.putObject(name);
            prop.put("type", type);
            prop.put("description", description);
            if (isRequired) required.add(name);
        }

        void propArray(String name, String description, boolean isRequired) {
            ObjectNode prop = properties.putObject(name);
            prop.put("type", "array");
            prop.put("description", description);
            ObjectNode items = prop.putObject("items");
            items.put("type", "object");
            if (isRequired) required.add(name);
        }

        JsonNode build() {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", properties);
            if (required.size() > 0) schema.set("required", required);
            return schema;
        }
    }
}
