package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool handlers invoked by lakeon-wiki-agent over /api/v1/internal/wiki/tool/*.
 * Each method is one tool; keeps business logic out of the controller layer.
 *
 * This class is stateless — it delegates file I/O to {@link WikiService}'s helpers
 * and database queries to {@link DocumentRepository}.
 */
@Service
public class WikiToolService {
    private static final Logger log = LoggerFactory.getLogger(WikiToolService.class);
    private static final String DOC_TYPE_WIKI = "wiki";
    private static final int READ_PAGE_MAX_CHARS = 32_000;
    private static final String TRUNCATION_MARKER = "\n\n[... truncated ...]";
    private static final java.util.Set<String> RESERVED_FILENAMES =
            java.util.Set.of("index.md", "log.md", "schema.md");

    private final WikiService wikiService;
    private final DocumentRepository documentRepository;
    private final ChunkService chunkService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ObjectMapper objectMapper;

    public WikiToolService(WikiService wikiService,
                           DocumentRepository documentRepository,
                           ChunkService chunkService,
                           KnowledgeBaseRepository knowledgeBaseRepository,
                           ObjectMapper objectMapper) {
        this.wikiService = wikiService;
        this.documentRepository = documentRepository;
        this.chunkService = chunkService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.objectMapper = objectMapper;
    }

    // ── Read tools ──────────────────────────────────────────────

    /** Return every wiki page in the KB with a one-line summary. */
    public List<Map<String, Object>> listPages(String tenantId, String kbId) {
        List<DocumentEntity> docs = documentRepository.findByTenantIdAndKbIdAndDocType(
                tenantId, kbId, DOC_TYPE_WIKI);
        List<Map<String, Object>> pages = docs.stream()
                .filter(d -> !RESERVED_FILENAMES.contains(d.getFilename()))
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("title", WikiService.filenameToTitle(d.getFilename()));
                    m.put("filename", d.getFilename());
                    m.put("summary", extractSummary(d));
                    m.put("updated_at", d.getUpdatedAt() != null ? d.getUpdatedAt().toString() : null);
                    return m;
                })
                .collect(Collectors.toList());
        log.debug("listPages tenant={} kb={} count={}", tenantId, kbId, pages.size());
        return pages;
    }

    /** Read the full markdown body of a single wiki page. */
    public Map<String, Object> readPage(String tenantId, String kbId, String title) {
        String filename = WikiService.titleToFilename(title);
        String content = wikiService.readWikiPage(tenantId, kbId, filename);
        if (content == null) {
            log.warn("readPage not found: tenant={} kb={} title={}", tenantId, kbId, title);
            return Map.of("found", false, "title", title);
        }
        if (content.length() > READ_PAGE_MAX_CHARS) {
            content = content.substring(0, READ_PAGE_MAX_CHARS - TRUNCATION_MARKER.length())
                    + TRUNCATION_MARKER;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("found", true);
        r.put("title", title);
        r.put("filename", filename);
        r.put("content", content);
        return r;
    }

    /** Keyword search across wiki page titles and summaries. */
    public List<Map<String, Object>> searchPages(String tenantId, String kbId, String query, int topK) {
        String q = query == null ? "" : query.toLowerCase();
        List<DocumentEntity> docs = documentRepository.findByTenantIdAndKbIdAndDocType(
                tenantId, kbId, DOC_TYPE_WIKI);
        List<Map<String, Object>> results = docs.stream()
                .filter(d -> !RESERVED_FILENAMES.contains(d.getFilename()))
                .map(d -> {
                    String title = WikiService.filenameToTitle(d.getFilename());
                    String summary = extractSummary(d);
                    int score = score(title.toLowerCase(), q) * 3 +
                                score(summary.toLowerCase(), q);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("title", title);
                    m.put("filename", d.getFilename());
                    m.put("summary", summary);
                    m.put("score", score);
                    return m;
                })
                .filter(m -> ((Integer) m.get("score")) > 0)
                .sorted((a, b) -> Integer.compare((Integer) b.get("score"), (Integer) a.get("score")))
                .limit(topK)
                .collect(Collectors.toList());
        log.debug("searchPages tenant={} kb={} query={} hits={}", tenantId, kbId, query, results.size());
        return results;
    }

    /** Read the source document's fulltext (for agent's initial read). */
    public Map<String, Object> readSource(String tenantId, String kbId, String documentId) {
        String fulltext = chunkService.getFulltext(tenantId, kbId, documentId);
        if (fulltext == null) {
            log.warn("readSource not found: tenant={} kb={} doc={}", tenantId, kbId, documentId);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("found", false);
            return r;
        }
        String filename = documentRepository.findById(documentId)
                .map(DocumentEntity::getFilename).orElse(documentId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("found", true);
        r.put("document_id", documentId);
        r.put("filename", filename);
        r.put("content", fulltext);
        return r;
    }

    /** Read the KB schema document — seeded on KB creation, co-evolved by the agent. */
    public String getSchema(String tenantId, String kbId) {
        String schema = wikiService.readWikiPage(tenantId, kbId, "schema.md");
        if (schema == null) {
            log.debug("getSchema missing for tenant={} kb={}", tenantId, kbId);
        }
        return schema != null ? schema : "";
    }

    // TODO(follow-up): DocumentEntity has no dedicated summary column; we read from metadata["summary"].
    //  Consider adding a proper summary column and migrating existing data so searchPages can filter at the DB layer.
    /**
     * Extract a one-line summary from the document. {@link DocumentEntity} has no
     * dedicated summary column — the wiki agent stores it in the metadata map under
     * the "summary" key. Returns empty string if absent.
     */
    private static String extractSummary(DocumentEntity d) {
        if (d.getMetadata() == null) return "";
        String s = d.getMetadata().get("summary");
        return s != null ? s : "";
    }

    private static int score(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isBlank()) return 0;
        int s = 0;
        for (String term : needle.split("\\s+")) {
            if (term.isBlank()) continue;
            if (haystack.contains(term)) s++;
        }
        return s;
    }
}
