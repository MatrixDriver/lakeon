package com.lakeon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lakeon.knowledge.ChunkService;
import com.lakeon.knowledge.KnowledgeService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class KnowledgeMcpTools {
    private final McpToolRegistry registry;
    private final KnowledgeService knowledgeService;
    private final ChunkService chunkService;
    private final ObjectMapper mapper;

    public KnowledgeMcpTools(McpToolRegistry registry, KnowledgeService knowledgeService,
                             ChunkService chunkService, ObjectMapper mapper) {
        this.registry = registry;
        this.knowledgeService = knowledgeService;
        this.chunkService = chunkService;
        this.mapper = mapper;
    }

    @PostConstruct
    void register() {
        registry.register("knowledge_list_bases",
                "List all knowledge bases for the current tenant. Use this to discover available knowledge bases before searching.",
                schema(b -> {
                    // no properties required
                }),
                (tenant, args) -> {
                    var bases = knowledgeService.listKnowledgeBases(tenant.getId());
                    return bases.stream().map(kb -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", kb.getId());
                        m.put("name", kb.getName());
                        m.put("description", kb.getDescription());
                        m.put("document_count", kb.getDocumentCount());
                        return m;
                    }).toList();
                });

        registry.register("knowledge_search",
                "Search a knowledge base by semantic similarity. Returns the most relevant chunks for the query. Use this to find information in uploaded documents.",
                schema(b -> {
                    b.prop("kb_id", "string", "Knowledge base ID", true);
                    b.prop("query", "string", "Search query", true);
                    b.prop("top_k", "integer", "Number of results to return (default 5)", false);
                }),
                (tenant, args) -> {
                    String kbId = args.get("kb_id").asText();
                    String query = args.get("query").asText();
                    int topK = args.has("top_k") ? args.get("top_k").asInt() : 5;
                    return knowledgeService.search(tenant.getId(), kbId, query, topK, null, null, true, null);
                });

        registry.register("knowledge_list_documents",
                "List all documents in a knowledge base. Use this to see what files have been uploaded and their processing status.",
                schema(b -> {
                    b.prop("kb_id", "string", "Knowledge base ID", true);
                }),
                (tenant, args) -> {
                    String kbId = args.get("kb_id").asText();
                    var docs = knowledgeService.listDocuments(tenant.getId(), kbId, null);
                    return docs.stream().map(doc -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", doc.getId());
                        m.put("filename", doc.getFilename());
                        m.put("format", doc.getFormat());
                        m.put("status", doc.getStatus().name());
                        m.put("chunks_count", doc.getChunksCount());
                        return m;
                    }).toList();
                });

        registry.register("knowledge_get_chunk",
                "Get a specific chunk from a document by index. Use this to read the full text of a chunk after finding it via search.",
                schema(b -> {
                    b.prop("kb_id", "string", "Knowledge base ID", true);
                    b.prop("document_id", "string", "Document ID", true);
                    b.prop("chunk_index", "integer", "Chunk index (0-based)", true);
                }),
                (tenant, args) -> {
                    String kbId = args.get("kb_id").asText();
                    String docId = args.get("document_id").asText();
                    int chunkIndex = args.get("chunk_index").asInt();
                    return chunkService.getChunk(tenant.getId(), kbId, docId, chunkIndex);
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

        JsonNode build() {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", properties);
            if (required.size() > 0) schema.set("required", required);
            return schema;
        }
    }
}
