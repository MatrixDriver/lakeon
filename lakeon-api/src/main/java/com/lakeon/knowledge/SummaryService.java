package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.*;

@Service
public class SummaryService {
    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private static final String SUMMARY_MODEL = "deepseek-v3.2";
    private static final int MAX_FULLTEXT_CHARS = 28_000;
    private static final String SUMMARY_PROMPT = """
            你是一个文档摘要助手。请为以下文档生成一份结构化摘要。

            要求：
            1. 用中文输出（除非原文是纯英文）
            2. 先用一句话概括文档主题
            3. 再列出3-7个关键要点
            4. 总长度控制在300-500字
            5. 保留专业术语原文

            文档内容：
            """;

    private static final String KB_SUMMARY_PROMPT = """
            你是一个知识库摘要助手。以下是一个知识库中所有文档的摘要。
            请生成一份知识库全局概览。

            要求：
            1. 用中文输出（除非所有摘要都是纯英文）
            2. 先用一句话概括知识库的主题和范围
            3. 列出知识库涵盖的主要主题（3-10个）
            4. 总长度控制在300-500字

            文档摘要列表：
            """;

    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ChunkService chunkService;

    public SummaryService(LakeonProperties props, ObjectMapper objectMapper,
                          ChunkService chunkService) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.chunkService = chunkService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Generate and store a document summary (level=1 chunk).
     * Uses the provided Connection to the compute pod DB.
     */
    public void summarizeDocument(Connection conn, String tenantId, String kbId,
                                  String documentId) {
        String fulltext = chunkService.getFulltext(tenantId, kbId, documentId);
        if (fulltext == null || fulltext.isBlank()) {
            log.warn("No fulltext found for doc {}, skipping summarization", documentId);
            return;
        }

        if (fulltext.length() > MAX_FULLTEXT_CHARS) {
            fulltext = fulltext.substring(0, MAX_FULLTEXT_CHARS);
        }

        String summary = callLlm(SUMMARY_PROMPT + fulltext);
        if (summary == null || summary.isBlank()) {
            throw new RuntimeException("LLM returned empty summary for doc " + documentId);
        }

        String embModel = props.getKnowledge().getEmbeddingModel();
        float[] embedding = chunkService.getEmbeddingPublic(summary, embModel);
        String vectorStr = chunkService.floatArrayToVectorLiteralPublic(embedding);

        int[] sourceChunkIds = getSourceChunkIds(conn, documentId);

        writeSummaryChunk(conn, documentId, 1, summary, vectorStr,
                sourceChunkIds, Map.of("type", "document_summary"));

        log.info("Document summary generated for doc {} ({} chars)", documentId, summary.length());
    }

    /**
     * Generate and store a KB-level summary (level=2 chunk).
     * Uses the provided Connection to the compute pod DB.
     */
    public void summarizeKb(Connection conn, String tenantId, String kbId) {
        List<Map<String, Object>> l1Chunks = readChunksByLevel(conn, 1);
        if (l1Chunks.isEmpty()) {
            log.warn("No L1 summaries found for KB {}, skipping KB summarization", kbId);
            return;
        }

        StringBuilder sb = new StringBuilder();
        List<Integer> sourceIds = new ArrayList<>();
        for (Map<String, Object> chunk : l1Chunks) {
            sb.append("---\n").append(chunk.get("content")).append("\n");
            sourceIds.add((Integer) chunk.get("id"));
        }

        String input = sb.toString();
        if (input.length() > MAX_FULLTEXT_CHARS) {
            input = input.substring(0, MAX_FULLTEXT_CHARS);
        }

        String summary = callLlm(KB_SUMMARY_PROMPT + input);
        if (summary == null || summary.isBlank()) {
            throw new RuntimeException("LLM returned empty KB summary for KB " + kbId);
        }

        String embModel = props.getKnowledge().getEmbeddingModel();
        float[] embedding = chunkService.getEmbeddingPublic(summary, embModel);
        String vectorStr = chunkService.floatArrayToVectorLiteralPublic(embedding);

        int[] sourceArr = sourceIds.stream().mapToInt(Integer::intValue).toArray();
        writeSummaryChunk(conn, "__kb_summary__", 2, summary, vectorStr,
                sourceArr, Map.of("type", "kb_summary"));

        log.info("KB summary generated for KB {} from {} document summaries", kbId, l1Chunks.size());
    }

    /**
     * Check if all documents in a KB have L1 summaries.
     */
    public boolean allDocumentsHaveSummary(Connection conn, List<String> documentIds) {
        if (documentIds.isEmpty()) return false;
        String placeholders = String.join(",", Collections.nCopies(documentIds.size(), "?"));
        String sql = "SELECT COUNT(DISTINCT document_id) FROM knowledge_chunks " +
                "WHERE level = 1 AND document_id IN (" + placeholders + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < documentIds.size(); i++) {
                ps.setString(i + 1, documentIds.get(i));
            }
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1) == documentIds.size();
        } catch (Exception e) {
            log.warn("Failed to check L1 completeness: {}", e.getMessage());
            return false;
        }
    }

    // ── Internal helpers ────────────────────────────────────────────

    private String callLlm(String prompt) {
        String apiKey = props.getAi().getApiKey();
        String baseUrl = props.getAi().getBaseUrl();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("AI API key not configured, cannot generate summary");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        String aiModel = props.getAi().getModel();
        requestBody.put("model", aiModel.isEmpty() ? SUMMARY_MODEL : aiModel);
        requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", 0.0);
        requestBody.put("max_tokens", 1024);

        try {
            String body = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw new RuntimeException("LLM API returned " + response.statusCode()
                        + ": " + response.body());
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("LLM API returned " + response.statusCode()
                        + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content")
                    .asText("").trim();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private void writeSummaryChunk(Connection conn, String documentId, int level,
                                   String content, String vectorStr, int[] sourceChunkIds,
                                   Map<String, String> metadata) {
        String metaJson;
        try {
            metaJson = objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            metaJson = "{}";
        }
        try {
            boolean wasAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM knowledge_chunks WHERE document_id = ? AND level = ?")) {
                del.setString(1, documentId);
                del.setInt(2, level);
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO knowledge_chunks " +
                    "(document_id, chunk_index, content, embedding, metadata, " +
                    "level, source_chunks, char_count, created_at) " +
                    "VALUES (?, 0, ?, ?::vector, ?::jsonb, ?, ?, ?, now())")) {
                ins.setString(1, documentId);
                ins.setString(2, content);
                ins.setString(3, vectorStr);
                ins.setString(4, metaJson);
                ins.setInt(5, level);
                if (sourceChunkIds != null && sourceChunkIds.length > 0) {
                    Integer[] boxed = Arrays.stream(sourceChunkIds).boxed().toArray(Integer[]::new);
                    ins.setArray(6, conn.createArrayOf("integer", boxed));
                } else {
                    ins.setNull(6, java.sql.Types.ARRAY);
                }
                ins.setInt(7, content.length());
                ins.executeUpdate();
            }
            conn.commit();
            conn.setAutoCommit(wasAutoCommit);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write summary chunk: " + e.getMessage(), e);
        }
    }

    private int[] getSourceChunkIds(Connection conn, String documentId) {
        String sql = "SELECT id FROM knowledge_chunks WHERE document_id = ? AND level = 0 ORDER BY chunk_index";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, documentId);
            ResultSet rs = ps.executeQuery();
            List<Integer> ids = new ArrayList<>();
            while (rs.next()) ids.add(rs.getInt("id"));
            return ids.stream().mapToInt(Integer::intValue).toArray();
        } catch (Exception e) {
            log.warn("Failed to get source chunk IDs for doc {}: {}", documentId, e.getMessage());
            return new int[0];
        }
    }

    private List<Map<String, Object>> readChunksByLevel(Connection conn, int level) {
        String sql = "SELECT id, document_id, content FROM knowledge_chunks WHERE level = ? ORDER BY document_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, level);
            ResultSet rs = ps.executeQuery();
            List<Map<String, Object>> chunks = new ArrayList<>();
            while (rs.next()) {
                chunks.add(Map.of(
                        "id", rs.getInt("id"),
                        "document_id", rs.getString("document_id"),
                        "content", rs.getString("content")
                ));
            }
            return chunks;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read level-" + level + " chunks: " + e.getMessage(), e);
        }
    }
}
