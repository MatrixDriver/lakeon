package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WikiToolServiceTest {
    WikiService wikiService;
    DocumentRepository documentRepository;
    ChunkService chunkService;
    KnowledgeBaseRepository knowledgeBaseRepository;
    WikiToolService tool;

    @BeforeEach
    void setup() {
        wikiService = mock(WikiService.class);
        documentRepository = mock(DocumentRepository.class);
        chunkService = mock(ChunkService.class);
        knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
        tool = new WikiToolService(wikiService, documentRepository, chunkService,
                                   knowledgeBaseRepository, new ObjectMapper());
    }

    @Test
    void listPagesSkipsIndexAndLog() {
        DocumentEntity a = newWikiDoc("database-sharding.md", "Database sharding summary");
        DocumentEntity b = newWikiDoc("index.md", "");
        DocumentEntity c = newWikiDoc("log.md", "");
        when(documentRepository.findByTenantIdAndKbIdAndDocType("t1", "kb1", "wiki"))
                .thenReturn(List.of(a, b, c));

        List<Map<String, Object>> pages = tool.listPages("t1", "kb1");

        assertEquals(1, pages.size());
        // filenameToTitle is a pure static helper — result is the filename minus ".md"
        assertEquals("database-sharding", pages.get(0).get("title"));
        assertEquals("database-sharding.md", pages.get(0).get("filename"));
        assertEquals("Database sharding summary", pages.get(0).get("summary"));
    }

    @Test
    void listPagesExcludesSchemaAndIndexAndLog() {
        DocumentEntity real = newWikiDoc("database-sharding.md", "Real page");
        DocumentEntity schema = newWikiDoc("schema.md", "");
        DocumentEntity index = newWikiDoc("index.md", "");
        DocumentEntity logDoc = newWikiDoc("log.md", "");
        when(documentRepository.findByTenantIdAndKbIdAndDocType("t1", "kb1", "wiki"))
                .thenReturn(List.of(real, schema, index, logDoc));

        List<Map<String, Object>> pages = tool.listPages("t1", "kb1");

        assertEquals(1, pages.size());
        assertEquals("database-sharding.md", pages.get(0).get("filename"));
    }

    @Test
    void readPageReturnsFoundFalseWhenMissing() {
        // titleToFilename is static and pure: "Ghost" -> "ghost.md"
        when(wikiService.readWikiPage("t1", "kb1", "ghost.md")).thenReturn(null);

        Map<String, Object> result = tool.readPage("t1", "kb1", "Ghost");

        assertEquals(false, result.get("found"));
        assertEquals("Ghost", result.get("title"));
    }

    @Test
    void readPageTruncatesLargeContent() {
        String huge = "x".repeat(40_000);
        when(wikiService.readWikiPage("t1", "kb1", "big.md")).thenReturn(huge);

        Map<String, Object> result = tool.readPage("t1", "kb1", "Big");

        assertEquals(true, result.get("found"));
        String content = (String) result.get("content");
        assertTrue(content.length() <= 32_000, "expected truncation, was " + content.length());
        assertTrue(content.endsWith("[... truncated ...]"));
    }

    @Test
    void readPageReturnsFullContentWhenSmall() {
        when(wikiService.readWikiPage("t1", "kb1", "small.md")).thenReturn("hello world");

        Map<String, Object> result = tool.readPage("t1", "kb1", "Small");

        assertEquals(true, result.get("found"));
        assertEquals("hello world", result.get("content"));
        assertEquals("small.md", result.get("filename"));
    }

    @Test
    void searchPagesScoresTitleHigherThanSummary() {
        DocumentEntity hit = newWikiDoc("auth.md", "nothing relevant here");
        DocumentEntity miss = newWikiDoc("chunking.md", "auth is discussed here");
        when(documentRepository.findByTenantIdAndKbIdAndDocType("t1", "kb1", "wiki"))
                .thenReturn(List.of(hit, miss));

        List<Map<String, Object>> results = tool.searchPages("t1", "kb1", "auth", 5);

        assertEquals(2, results.size());
        // title "auth" matches with weight 3 -> score 3 vs summary-only -> score 1
        assertEquals("auth", results.get(0).get("title"));
        assertTrue((Integer) results.get(0).get("score") > (Integer) results.get(1).get("score"));
    }

    @Test
    void searchPagesFiltersZeroScoreAndRespectsTopK() {
        DocumentEntity a = newWikiDoc("auth.md", "");
        DocumentEntity b = newWikiDoc("indexing.md", "auth mentioned");
        DocumentEntity c = newWikiDoc("unrelated.md", "nothing here");
        when(documentRepository.findByTenantIdAndKbIdAndDocType("t1", "kb1", "wiki"))
                .thenReturn(List.of(a, b, c));

        List<Map<String, Object>> results = tool.searchPages("t1", "kb1", "auth", 1);

        assertEquals(1, results.size());
        assertEquals("auth", results.get(0).get("title"));
    }

    @Test
    void readSourceReturnsFoundFalseWhenFulltextMissing() {
        when(chunkService.getFulltext("t1", "kb1", "doc_x")).thenReturn(null);

        Map<String, Object> result = tool.readSource("t1", "kb1", "doc_x");

        assertEquals(false, result.get("found"));
    }

    @Test
    void readSourceReturnsFulltextAndFilename() {
        DocumentEntity d = newWikiDoc("paper.pdf", "");
        d.setId("doc_x");
        when(chunkService.getFulltext("t1", "kb1", "doc_x")).thenReturn("the content");
        when(documentRepository.findById("doc_x")).thenReturn(Optional.of(d));

        Map<String, Object> result = tool.readSource("t1", "kb1", "doc_x");

        assertEquals(true, result.get("found"));
        assertEquals("doc_x", result.get("document_id"));
        assertEquals("paper.pdf", result.get("filename"));
        assertEquals("the content", result.get("content"));
    }

    @Test
    void getSchemaReturnsEmptyStringWhenMissing() {
        when(wikiService.readWikiPage("t1", "kb1", "schema.md")).thenReturn(null);

        assertEquals("", tool.getSchema("t1", "kb1"));
    }

    @Test
    void getSchemaReturnsContentWhenPresent() {
        when(wikiService.readWikiPage("t1", "kb1", "schema.md")).thenReturn("# Schema\n\nFoo");

        assertEquals("# Schema\n\nFoo", tool.getSchema("t1", "kb1"));
    }

    private DocumentEntity newWikiDoc(String filename, String summary) {
        DocumentEntity d = new DocumentEntity();
        d.setFilename(filename);
        d.setDocType("wiki");
        d.setUpdatedAt(Instant.now());
        Map<String, String> metadata = new HashMap<>();
        if (summary != null && !summary.isEmpty()) {
            metadata.put("summary", summary);
        }
        d.setMetadata(metadata);
        return d;
    }
}
