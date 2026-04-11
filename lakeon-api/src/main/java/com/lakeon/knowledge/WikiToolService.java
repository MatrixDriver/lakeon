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

    // ── Write tools ─────────────────────────────────────────────

    /**
     * Create a new wiki page. Refuses if a page with the same (case-insensitive) title exists.
     * The first non-blank line of content is stored as metadata["summary"] so listPages can
     * surface it without reading the full body.
     */
    public Map<String, Object> createPage(String tenantId, String kbId, String title,
                                          String content, List<String> tags) {
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            return Map.of("ok", false, "error", "title and content are required");
        }
        String filename = WikiService.titleToFilename(title);
        if (RESERVED_FILENAMES.contains(filename)) {
            return Map.of("ok", false, "error", "reserved filename: " + filename);
        }
        if (wikiService.readWikiPage(tenantId, kbId, filename) != null) {
            return Map.of("ok", false, "error", "page already exists: " + title);
        }
        wikiService.writeWikiDocument(tenantId, kbId, filename, title, content);
        // Store a short summary in metadata so listPages/searchPages can surface it
        // TODO(perf): extend writeWikiDocument to accept a summary so we don't re-fetch+save here.
        storeSummary(tenantId, kbId, filename, firstNonBlankLine(content, 200));
        log.info("Agent created wiki page {} in KB {}", title, kbId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("filename", filename);
        return result;
    }

    /**
     * Update a wiki page by exact substring replacement. old_text must match exactly once.
     */
    public Map<String, Object> updatePage(String tenantId, String kbId, String title,
                                          String oldText, String newText) {
        if (title == null || title.isBlank() || oldText == null || oldText.isEmpty()) {
            return Map.of("ok", false, "error", "title and old_text are required");
        }
        String filename = WikiService.titleToFilename(title);
        if (RESERVED_FILENAMES.contains(filename)) {
            return Map.of("ok", false,
                "error", "cannot edit reserved file: " + filename + " (use logNote for log entries)");
        }
        String current = wikiService.readWikiPage(tenantId, kbId, filename);
        if (current == null) {
            return Map.of("ok", false, "error", "page not found: " + title);
        }
        int occurrences = countOccurrences(current, oldText);
        if (occurrences == 0) {
            return Map.of("ok", false, "error",
                    "old_text not found in " + title + "; use list_pages to see available titles or read_page to inspect this page's content");
        }
        if (occurrences > 1) {
            return Map.of("ok", false, "error",
                    "old_text matches " + occurrences + " places in " + title +
                    "; expand old_text with surrounding lines until it uniquely identifies the target, or use append_page to add new content at the end");
        }
        String updated = current.replace(oldText, newText == null ? "" : newText);
        wikiService.writeWikiDocument(tenantId, kbId, filename, title, updated);
        log.info("Agent updated wiki page {} in KB {}", title, kbId);
        return Map.of("ok", true);
    }

    /**
     * Append content to the end of an existing wiki page.
     */
    public Map<String, Object> appendPage(String tenantId, String kbId, String title, String content) {
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            return Map.of("ok", false, "error", "title and content are required");
        }
        String filename = WikiService.titleToFilename(title);
        if (RESERVED_FILENAMES.contains(filename)) {
            return Map.of("ok", false,
                "error", "cannot edit reserved file: " + filename + " (use logNote for log entries)");
        }
        String current = wikiService.readWikiPage(tenantId, kbId, filename);
        if (current == null) {
            return Map.of("ok", false, "error", "page not found: " + title);
        }
        String updated = current + "\n\n" + content;
        wikiService.writeWikiDocument(tenantId, kbId, filename, title, updated);
        log.info("Agent appended to wiki page {} in KB {}", title, kbId);
        return Map.of("ok", true);
    }

    /**
     * Delete a wiki page. Refuses to delete reserved files (schema.md, index.md, log.md).
     */
    public Map<String, Object> deletePage(String tenantId, String kbId, String title) {
        if (title == null || title.isBlank()) {
            return Map.of("ok", false, "error", "title is required");
        }
        String filename = WikiService.titleToFilename(title);
        if (RESERVED_FILENAMES.contains(filename)) {
            return Map.of("ok", false, "error", "cannot delete reserved file: " + filename);
        }
        Optional<DocumentEntity> docOpt = documentRepository.findByTypeAndFilename(
                tenantId, kbId, DOC_TYPE_WIKI, filename);
        if (docOpt.isEmpty()) {
            return Map.of("ok", false, "error", "page not found: " + title);
        }
        documentRepository.delete(docOpt.get());
        log.info("Agent deleted wiki page {} in KB {}", title, kbId);
        return Map.of("ok", true);
    }

    /**
     * Append a line to the KB's log.md.
     */
    public Map<String, Object> logNote(String tenantId, String kbId, String message) {
        if (message == null || message.isBlank()) {
            return Map.of("ok", false, "error", "message is required");
        }
        wikiService.appendToLog(tenantId, kbId, message);
        return Map.of("ok", true);
    }

    // ── Helpers ─────────────────────────────────────────────────

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String firstNonBlankLine(String content, int max) {
        if (content == null) return "";
        for (String line : content.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isBlank()) continue;
            // Strip leading markdown heading markers
            trimmed = trimmed.replaceAll("^#+\\s*", "");
            if (trimmed.isBlank()) continue;
            return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
        }
        return "";
    }

    private void storeSummary(String tenantId, String kbId, String filename, String summary) {
        try {
            Optional<DocumentEntity> docOpt = documentRepository.findByTypeAndFilename(
                    tenantId, kbId, DOC_TYPE_WIKI, filename);
            docOpt.ifPresent(doc -> {
                if (doc.getMetadata() == null) {
                    doc.setMetadata(new LinkedHashMap<>());
                }
                doc.getMetadata().put("summary", summary);
                documentRepository.save(doc);
            });
        } catch (Exception e) {
            log.warn("Failed to store summary for {}: {}", filename, e.getMessage());
        }
    }
}
