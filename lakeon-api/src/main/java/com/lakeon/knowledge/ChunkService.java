package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChunkService {
    private static final Logger log = LoggerFactory.getLogger(ChunkService.class);

    private final KnowledgeDbHelper dbHelper;
    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final DocumentRepository documentRepository;
    private final RestTemplate restTemplate;

    public ChunkService(KnowledgeDbHelper dbHelper,
                        LakeonProperties props,
                        ObjectMapper objectMapper,
                        DocumentRepository documentRepository) {
        this.dbHelper = dbHelper;
        this.props = props;
        this.objectMapper = objectMapper;
        this.documentRepository = documentRepository;
        this.restTemplate = new RestTemplate();
    }

    /**
     * List chunks for a document (paginated).
     */
    public Map<String, Object> listChunks(String tenantId, String kbId, String docId,
                                           int level, int offset, int limit) {
        String sql = "SELECT id, chunk_index, content, metadata, char_count, overlap_prev, " +
                "char_offset_start, char_offset_end, page_start, page_end, level, edited, " +
                "created_at, updated_at " +
                "FROM knowledge_chunks " +
                "WHERE document_id = ? AND level = ? " +
                "ORDER BY chunk_index " +
                "LIMIT ? OFFSET ?";
        String countSql = "SELECT count(*) FROM knowledge_chunks WHERE document_id = ? AND level = ?";

        List<Map<String, Object>> chunks = new ArrayList<>();
        int total = 0;

        try (Connection conn = dbHelper.getComputeConnection(tenantId, kbId)) {
            // Get total count
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                ps.setString(1, docId);
                ps.setInt(2, level);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) total = rs.getInt(1);
                }
            }

            // Get chunks
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, docId);
                ps.setInt(2, level);
                ps.setInt(3, limit);
                ps.setInt(4, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        chunks.add(rowToChunkMap(rs));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to list chunks for doc {} in kb {}: {}", docId, kbId, e.getMessage(), e);
            throw new RuntimeException("Failed to list chunks: " + e.getMessage(), e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunks", chunks);
        result.put("total", total);
        result.put("offset", offset);
        result.put("limit", limit);
        return result;
    }

    /**
     * Get a single chunk by document + chunk_index.
     */
    public Map<String, Object> getChunk(String tenantId, String kbId, String docId, int chunkIndex) {
        String sql = "SELECT id, chunk_index, content, metadata, char_count, overlap_prev, " +
                "char_offset_start, char_offset_end, page_start, page_end, level, edited, " +
                "created_at, updated_at " +
                "FROM knowledge_chunks WHERE document_id = ? AND chunk_index = ? AND level = 0";

        try (Connection conn = dbHelper.getComputeConnection(tenantId, kbId);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docId);
            ps.setInt(2, chunkIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rowToChunkMap(rs);
                }
            }
        } catch (Exception e) {
            log.error("Failed to get chunk {} for doc {} in kb {}: {}", chunkIndex, docId, kbId, e.getMessage(), e);
            throw new RuntimeException("Failed to get chunk: " + e.getMessage(), e);
        }

        throw new NotFoundException("Chunk not found: document=" + docId + " chunk_index=" + chunkIndex);
    }

    /**
     * Get adjacent chunks (chunk_index +/- 1) for context.
     */
    public Map<String, Object> getChunkContext(String tenantId, String kbId, String docId, int chunkIndex) {
        String sql = "SELECT chunk_index, content, char_count, metadata " +
                "FROM knowledge_chunks " +
                "WHERE document_id = ? AND level = 0 AND chunk_index IN (?, ?) " +
                "ORDER BY chunk_index";

        Map<String, Object> prev = null;
        Map<String, Object> next = null;

        try (Connection conn = dbHelper.getComputeConnection(tenantId, kbId);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docId);
            ps.setInt(2, chunkIndex - 1);
            ps.setInt(3, chunkIndex + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int idx = rs.getInt("chunk_index");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("chunk_index", idx);
                    row.put("content", rs.getString("content"));
                    row.put("char_count", rs.getInt("char_count"));
                    row.put("metadata", parseMetadata(rs.getString("metadata")));
                    if (idx == chunkIndex - 1) {
                        prev = row;
                    } else if (idx == chunkIndex + 1) {
                        next = row;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to get chunk context for chunk {} doc {} kb {}: {}", chunkIndex, docId, kbId, e.getMessage(), e);
            throw new RuntimeException("Failed to get chunk context: " + e.getMessage(), e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prev", prev);
        result.put("next", next);
        return result;
    }

    /**
     * Get chunk stats: length distribution, anomalies, adjacent similarity, duplicates.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getChunkStats(String tenantId, String kbId, String docId) {
        String lengthSql = "SELECT width_bucket(char_count, 0, 1200, 12) AS bucket, count(*) " +
                "FROM knowledge_chunks WHERE document_id = ? AND level = 0 " +
                "GROUP BY bucket ORDER BY bucket";

        String anomalySql = "SELECT id, chunk_index, char_count FROM knowledge_chunks " +
                "WHERE document_id = ? AND level = 0 AND (char_count < 80 OR char_count > 800)";

        String similaritySql = "SELECT a.chunk_index, " +
                "1 - (a.embedding <=> b.embedding) AS similarity " +
                "FROM knowledge_chunks a " +
                "JOIN knowledge_chunks b ON b.document_id = a.document_id " +
                "  AND b.chunk_index = a.chunk_index + 1 AND b.level = 0 " +
                "WHERE a.document_id = ? AND a.level = 0";

        String duplicateSql = "SELECT id, chunk_index, metadata->'duplicate_of' AS duplicate_of, " +
                "(metadata->>'similarity')::float AS similarity " +
                "FROM knowledge_chunks " +
                "WHERE document_id = ? AND level = 0 AND metadata ? 'duplicate_of'";

        String summarySql = "SELECT count(*) AS total_chunks, " +
                "coalesce(avg(char_count), 0) AS avg_char_count " +
                "FROM knowledge_chunks WHERE document_id = ? AND level = 0";

        List<Map<String, Object>> lengthDist = new ArrayList<>();
        List<Map<String, Object>> anomalies = new ArrayList<>();
        List<Map<String, Object>> similarities = new ArrayList<>();
        List<Map<String, Object>> duplicates = new ArrayList<>();
        int totalChunks = 0;
        double avgCharCount = 0;

        try (Connection conn = dbHelper.getComputeConnection(tenantId, kbId)) {
            // 1. Length distribution
            try (PreparedStatement ps = conn.prepareStatement(lengthSql)) {
                ps.setString(1, docId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("bucket", rs.getInt("bucket"));
                        row.put("count", rs.getLong(2));
                        lengthDist.add(row);
                    }
                }
            }

            // 2. Anomalous chunks
            try (PreparedStatement ps = conn.prepareStatement(anomalySql)) {
                ps.setString(1, docId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getString("id"));
                        row.put("chunk_index", rs.getInt("chunk_index"));
                        row.put("char_count", rs.getInt("char_count"));
                        anomalies.add(row);
                    }
                }
            }

            // 3. Adjacent semantic similarity
            try (PreparedStatement ps = conn.prepareStatement(similaritySql)) {
                ps.setString(1, docId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("chunk_index", rs.getInt("chunk_index"));
                        row.put("similarity", rs.getDouble("similarity"));
                        similarities.add(row);
                    }
                }
            }

            // 4. Duplicates
            try (PreparedStatement ps = conn.prepareStatement(duplicateSql)) {
                ps.setString(1, docId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getString("id"));
                        row.put("chunk_index", rs.getInt("chunk_index"));
                        String dupOf = rs.getString("duplicate_of");
                        row.put("duplicate_of", parseMetadata(dupOf));
                        row.put("similarity", rs.getDouble("similarity"));
                        duplicates.add(row);
                    }
                }
            }

            // 5. Summary
            try (PreparedStatement ps = conn.prepareStatement(summarySql)) {
                ps.setString(1, docId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        totalChunks = rs.getInt("total_chunks");
                        avgCharCount = rs.getDouble("avg_char_count");
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to get chunk stats for doc {} in kb {}: {}", docId, kbId, e.getMessage(), e);
            throw new RuntimeException("Failed to get chunk stats: " + e.getMessage(), e);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_chunks", totalChunks);
        summary.put("avg_char_count", Math.round(avgCharCount));
        summary.put("anomaly_count", anomalies.size());
        summary.put("duplicate_count", duplicates.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("length_distribution", lengthDist);
        result.put("anomalies", anomalies);
        result.put("adjacent_similarity", similarities);
        result.put("duplicates", duplicates);
        return result;
    }

    /**
     * Get fulltext from OBS (no compute needed).
     */
    public String getFulltext(String tenantId, String kbId, String docId) {
        String key = String.format("knowledge/%s/%s/%s/fulltext.md", tenantId, kbId, docId);

        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(props.getObs().getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
                .region(Region.of("cn-north-4"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();

        try {
            ResponseInputStream<GetObjectResponse> resp = s3.getObject(GetObjectRequest.builder()
                    .bucket(props.getObs().getBucket())
                    .key(key)
                    .build());
            return new String(resp.readAllBytes(), StandardCharsets.UTF_8);
        } catch (NoSuchKeyException e) {
            throw new NotFoundException("Fulltext not found for document: " + docId);
        } catch (Exception e) {
            log.error("Failed to read fulltext from OBS for doc {}: {}", docId, e.getMessage(), e);
            throw new RuntimeException("Failed to read fulltext: " + e.getMessage(), e);
        } finally {
            s3.close();
        }
    }

    /**
     * List chunks across all documents in KB (for global chunks tab).
     * Optionally filtered by docId and status (not yet used but reserved).
     */
    public Map<String, Object> listKbChunks(String tenantId, String kbId,
                                             String docId, String status,
                                             int offset, int limit) {
        StringBuilder sqlBuilder = new StringBuilder(
                "SELECT id, document_id, chunk_index, content, metadata, char_count, overlap_prev, " +
                "char_offset_start, char_offset_end, page_start, page_end, level, edited, " +
                "created_at, updated_at " +
                "FROM knowledge_chunks WHERE level = 0");
        StringBuilder countBuilder = new StringBuilder(
                "SELECT count(*) FROM knowledge_chunks WHERE level = 0");

        List<Object> params = new ArrayList<>();
        List<Object> countParams = new ArrayList<>();

        if (docId != null && !docId.isBlank()) {
            sqlBuilder.append(" AND document_id = ?");
            countBuilder.append(" AND document_id = ?");
            params.add(docId);
            countParams.add(docId);
        }

        sqlBuilder.append(" ORDER BY document_id, chunk_index LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<Map<String, Object>> chunks = new ArrayList<>();
        int total = 0;

        try (Connection conn = dbHelper.getComputeConnection(tenantId, kbId)) {
            // Count
            try (PreparedStatement ps = conn.prepareStatement(countBuilder.toString())) {
                for (int i = 0; i < countParams.size(); i++) {
                    ps.setObject(i + 1, countParams.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) total = rs.getInt(1);
                }
            }

            // Data
            try (PreparedStatement ps = conn.prepareStatement(sqlBuilder.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = rowToChunkMap(rs);
                        // Include document_id for KB-level listing
                        row.put("document_id", rs.getString("document_id"));
                        chunks.add(row);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to list KB chunks for kb {}: {}", kbId, e.getMessage(), e);
            throw new RuntimeException("Failed to list KB chunks: " + e.getMessage(), e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunks", chunks);
        result.put("total", total);
        result.put("offset", offset);
        result.put("limit", limit);
        return result;
    }

    // ── Write operations ─────────────────────────────────────────────

    /**
     * Edit a chunk's content and regenerate its embedding.
     */
    public Map<String, Object> editChunk(String tenantId, String kbId, String docId,
                                          int chunkIndex, String newContent) {
        // 1. Get new embedding
        float[] embedding = getEmbedding(newContent);
        String vectorStr = floatArrayToVectorLiteral(embedding);

        String updateSql = "UPDATE knowledge_chunks SET content = ?, embedding = ?::vector, " +
                "char_count = ?, edited = true, updated_at = now() " +
                "WHERE document_id = ? AND chunk_index = ? AND level = 0 " +
                "RETURNING id, chunk_index, content, metadata, char_count, overlap_prev, " +
                "char_offset_start, char_offset_end, page_start, page_end, level, edited, " +
                "created_at, updated_at";

        try (Connection conn = dbHelper.getComputeConnection(tenantId, kbId);
             PreparedStatement ps = conn.prepareStatement(updateSql)) {
            ps.setString(1, newContent);
            ps.setString(2, vectorStr);
            ps.setInt(3, newContent.length());
            ps.setString(4, docId);
            ps.setInt(5, chunkIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rowToChunkMap(rs);
                }
            }
        } catch (Exception e) {
            log.error("Failed to edit chunk {} for doc {} in kb {}: {}", chunkIndex, docId, kbId, e.getMessage(), e);
            throw new RuntimeException("Failed to edit chunk: " + e.getMessage(), e);
        }

        throw new NotFoundException("Chunk not found: document=" + docId + " chunk_index=" + chunkIndex);
    }

    /**
     * Delete a chunk and reindex subsequent chunks.
     */
    public void deleteChunk(String tenantId, String kbId, String docId, int chunkIndex) {
        String deleteSql = "DELETE FROM knowledge_chunks WHERE document_id = ? AND chunk_index = ? AND level = 0";
        String reindexSql = "UPDATE knowledge_chunks SET chunk_index = chunk_index - 1 " +
                "WHERE document_id = ? AND chunk_index > ? AND level = 0";

        try (Connection conn = dbHelper.getComputeConnection(tenantId, kbId)) {
            conn.setAutoCommit(false);
            try {
                // 1. Delete the chunk
                int deleted;
                try (PreparedStatement ps = conn.prepareStatement(deleteSql)) {
                    ps.setString(1, docId);
                    ps.setInt(2, chunkIndex);
                    deleted = ps.executeUpdate();
                }
                if (deleted == 0) {
                    conn.rollback();
                    throw new NotFoundException("Chunk not found: document=" + docId + " chunk_index=" + chunkIndex);
                }

                // 2. Reindex subsequent chunks
                try (PreparedStatement ps = conn.prepareStatement(reindexSql)) {
                    ps.setString(1, docId);
                    ps.setInt(2, chunkIndex);
                    ps.executeUpdate();
                }

                conn.commit();
            } catch (NotFoundException e) {
                throw e;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to delete chunk {} for doc {} in kb {}: {}", chunkIndex, docId, kbId, e.getMessage(), e);
            throw new RuntimeException("Failed to delete chunk: " + e.getMessage(), e);
        }

        // 3. Update chunksCount in RDS
        DocumentEntity doc = documentRepository.findByIdAndTenantId(docId, tenantId).orElse(null);
        if (doc != null && doc.getChunksCount() != null) {
            doc.setChunksCount(doc.getChunksCount() - 1);
            documentRepository.save(doc);
        }
    }

    /**
     * Create a new chunk inserted after the given index, reindexing subsequent chunks.
     */
    public Map<String, Object> createChunk(String tenantId, String kbId, String docId,
                                            String content, int insertAfterIndex) {
        // 1. Get embedding
        float[] embedding = getEmbedding(content);
        String vectorStr = floatArrayToVectorLiteral(embedding);
        int newIndex = insertAfterIndex + 1;

        String reindexSql = "UPDATE knowledge_chunks SET chunk_index = chunk_index + 1 " +
                "WHERE document_id = ? AND chunk_index >= ? AND level = 0";
        String insertSql = "INSERT INTO knowledge_chunks " +
                "(id, document_id, chunk_index, content, embedding, char_count, edited, level, " +
                "overlap_prev, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?::vector, ?, true, 0, 0, now(), now()) " +
                "RETURNING id, chunk_index, content, metadata, char_count, overlap_prev, " +
                "char_offset_start, char_offset_end, page_start, page_end, level, edited, " +
                "created_at, updated_at";

        Map<String, Object> result;
        try (Connection conn = dbHelper.getComputeConnection(tenantId, kbId)) {
            conn.setAutoCommit(false);
            try {
                // 2. Shift subsequent chunks up
                try (PreparedStatement ps = conn.prepareStatement(reindexSql)) {
                    ps.setString(1, docId);
                    ps.setInt(2, newIndex);
                    ps.executeUpdate();
                }

                // 3. Insert the new chunk
                String chunkId = "chk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, chunkId);
                    ps.setString(2, docId);
                    ps.setInt(3, newIndex);
                    ps.setString(4, content);
                    ps.setString(5, vectorStr);
                    ps.setInt(6, content.length());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            result = rowToChunkMap(rs);
                        } else {
                            throw new RuntimeException("INSERT did not return a row");
                        }
                    }
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            log.error("Failed to create chunk for doc {} in kb {}: {}", docId, kbId, e.getMessage(), e);
            throw new RuntimeException("Failed to create chunk: " + e.getMessage(), e);
        }

        // 4. Update chunksCount in RDS
        DocumentEntity doc = documentRepository.findByIdAndTenantId(docId, tenantId).orElse(null);
        if (doc != null) {
            doc.setChunksCount(doc.getChunksCount() != null ? doc.getChunksCount() + 1 : 1);
            documentRepository.save(doc);
        }

        return result;
    }

    // ── Internal helpers ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private float[] getEmbedding(String text) {
        String apiUrl = props.getKnowledge().getEmbeddingApiUrl();
        String apiKey = props.getKnowledge().getEmbeddingApiKey();
        String model = props.getKnowledge().getEmbeddingModel();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("Authorization", "Bearer " + apiKey);
        }

        Map<String, Object> body = Map.of(
                "model", model,
                "input", List.of(text),
                "encoding_format", "float"
        );
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        Map<String, Object> response = restTemplate.postForObject(apiUrl, request, Map.class);
        if (response == null || !response.containsKey("data")) {
            throw new RuntimeException("Failed to get embedding: empty response from embedding API");
        }
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("Failed to get embedding: no data returned");
        }
        List<Number> embeddingList = (List<Number>) data.get(0).get("embedding");
        float[] result = new float[embeddingList.size()];
        for (int i = 0; i < embeddingList.size(); i++) {
            result[i] = embeddingList.get(i).floatValue();
        }
        return result;
    }

    private String floatArrayToVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private Map<String, Object> rowToChunkMap(ResultSet rs) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("chunk_index", rs.getInt("chunk_index"));
        row.put("content", rs.getString("content"));
        row.put("metadata", parseMetadata(rs.getString("metadata")));
        row.put("char_count", rs.getInt("char_count"));
        row.put("overlap_prev", rs.getInt("overlap_prev"));
        row.put("char_offset_start", rs.getObject("char_offset_start"));
        row.put("char_offset_end", rs.getObject("char_offset_end"));
        row.put("page_start", rs.getObject("page_start"));
        row.put("page_end", rs.getObject("page_end"));
        row.put("level", rs.getInt("level"));
        row.put("edited", rs.getBoolean("edited"));
        row.put("created_at", rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null);
        row.put("updated_at", rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toString() : null);
        return row;
    }

    @SuppressWarnings("unchecked")
    private Object parseMetadata(String metaStr) {
        if (metaStr == null) return null;
        try {
            return objectMapper.readValue(metaStr, Map.class);
        } catch (Exception e) {
            return metaStr;
        }
    }
}
