package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Wiki Agent service: processes newly ingested documents and automatically
 * creates/updates wiki pages using LLM analysis.
 */
@Service
public class WikiService {
    private static final Logger log = LoggerFactory.getLogger(WikiService.class);

    private static final String DEEPSEEK_MODEL = "deepseek-chat";
    private static final int MAX_FULLTEXT_CHARS = 28_000;
    private static final int MAX_INDEX_CHARS = 8_000;
    private static final String DOC_TYPE_WIKI = "wiki";
    private static final DateTimeFormatter LOG_TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private static final String DEFAULT_WIKI_AGENT_PROMPT = """
            You are a wiki maintenance agent for a knowledge base. A new document has been ingested.
            Your task is to analyze the document and determine which wiki pages to create or update.

            Current wiki index (index.md):
            ---
            %s
            ---

            New document content:
            ---
            %s
            ---

            Instructions:
            1. Identify key topics, concepts, and entities in the new document.
            2. For each topic, decide whether to CREATE a new wiki page or UPDATE an existing one.
            3. Wiki pages should be concise reference articles (not copies of the source).
            4. Use [[wikilinks]] to cross-reference between pages.
            5. Each wiki page title should be a clear noun phrase (e.g. "Database Sharding", "API Authentication").
            6. Write in the same language as the source document.

            Output a JSON object with this exact structure:
            {
              "wiki_pages": [
                {"title": "Page Title", "action": "create", "content": "Full markdown content with [[wikilinks]]..."},
                {"title": "Existing Page", "action": "update", "content": "Updated full markdown content..."}
              ],
              "index_updates": [
                {"title": "Page Title", "summary": "One-line summary of this page"}
              ],
              "log_entry": "Brief description of what was updated"
            }

            If no wiki changes are needed, return: {"wiki_pages": [], "index_updates": [], "log_entry": "No changes needed"}
            """;

    private static final String DEFAULT_CURATE_PROMPT = """
            You are a wiki curator agent. Your task is to review and reorganize an existing knowledge base wiki.

            Current wiki index (index.md):
            ---
            %s
            ---

            Current wiki pages:
            ---
            %s
            ---

            Instructions:
            1. Review all wiki pages for consistency, completeness, and organization.
            2. Merge pages that cover the same topic or have significant overlap.
            3. Split pages that are too broad into more focused articles.
            4. Fix broken or missing [[wikilinks]] between related pages.
            5. Improve page structure: add clear headings, remove redundancy, ensure accuracy.
            6. Update the index to reflect the reorganized structure.
            7. Write in the same language as the source content.

            Output a JSON object with this exact structure:
            {
              "wiki_pages": [
                {"title": "Page Title", "action": "update", "content": "Reorganized full markdown content with [[wikilinks]]..."},
                {"title": "Merged Page", "action": "create", "content": "Content merged from multiple pages..."}
              ],
              "delete_pages": ["Old Page To Remove"],
              "index_updates": [
                {"title": "Page Title", "summary": "Updated one-line summary"}
              ],
              "log_entry": "Brief description of reorganization"
            }

            If no changes are needed, return: {"wiki_pages": [], "delete_pages": [], "index_updates": [], "log_entry": "No changes needed"}
            """;

    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ChunkService chunkService;
    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KbWriteQueue kbWriteQueue;
    private final KnowledgeService knowledgeService;

    public WikiService(LakeonProperties props,
                       ObjectMapper objectMapper,
                       ChunkService chunkService,
                       DocumentRepository documentRepository,
                       KnowledgeBaseRepository knowledgeBaseRepository,
                       KbWriteQueue kbWriteQueue,
                       KnowledgeService knowledgeService) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.chunkService = chunkService;
        this.documentRepository = documentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.kbWriteQueue = kbWriteQueue;
        this.knowledgeService = knowledgeService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Returns the configured ingest prompt, or the default if not set.
     */
    public String getIngestPrompt() {
        String custom = props.getWiki() != null ? props.getWiki().getIngestPrompt() : null;
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        return DEFAULT_WIKI_AGENT_PROMPT;
    }

    /**
     * Returns the configured curate prompt, or the default if not set.
     */
    public String getCuratePrompt() {
        String custom = props.getWiki() != null ? props.getWiki().getCuratePrompt() : null;
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        return DEFAULT_CURATE_PROMPT;
    }

    /**
     * Returns the configured wiki model, or the default DEEPSEEK_MODEL if not set.
     */
    public String getModel() {
        String custom = props.getWiki() != null ? props.getWiki().getModel() : null;
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        return DEEPSEEK_MODEL;
    }

    /**
     * Main entry point: process a newly ingested document and create/update wiki pages.
     * Called by KbWriteQueue after document summarization completes.
     */
    public void processIngest(String tenantId, String kbId, String documentId) {
        log.info("Wiki agent processing document {} in KB {}", documentId, kbId);

        // 1. Read the source document fulltext
        String fulltext = chunkService.getFulltext(tenantId, kbId, documentId);
        if (fulltext == null || fulltext.isBlank()) {
            log.warn("No fulltext found for doc {}, skipping wiki update", documentId);
            return;
        }
        if (fulltext.length() > MAX_FULLTEXT_CHARS) {
            fulltext = fulltext.substring(0, MAX_FULLTEXT_CHARS);
        }

        // 2. Read current wiki index
        String currentIndex = readWikiPage(tenantId, kbId, "index.md");
        if (currentIndex == null) {
            currentIndex = "# Wiki Index\n\nNo pages yet.\n";
        }
        if (currentIndex.length() > MAX_INDEX_CHARS) {
            currentIndex = currentIndex.substring(0, MAX_INDEX_CHARS);
        }

        // 3. Call LLM to determine wiki changes
        String prompt = String.format(getIngestPrompt(), currentIndex, fulltext);
        String llmResponse = callDeepSeek(prompt);
        if (llmResponse == null || llmResponse.isBlank()) {
            log.warn("LLM returned empty response for wiki update on doc {}", documentId);
            return;
        }

        // 4. Parse and apply changes
        try {
            JsonNode response = objectMapper.readTree(llmResponse);
            applyWikiChanges(tenantId, kbId, response, currentIndex);
        } catch (Exception e) {
            log.error("Failed to parse/apply wiki changes for doc {}: {}", documentId, e.getMessage(), e);
        }
    }

    /**
     * Save a chat response as a wiki page.
     */
    public void saveResponse(String tenantId, String kbId, String title, String content) {
        String filename = titleToFilename(title);
        writeWikiDocument(tenantId, kbId, filename, title, content);
        appendToLog(tenantId, kbId, "[对话沉淀] 保存页面: " + title);
        log.info("Saved chat response as wiki page: {} in KB {}", title, kbId);

        // Increment settlement count
        try {
            KnowledgeBaseEntity kbEntity = knowledgeBaseRepository.findById(kbId).orElse(null);
            if (kbEntity != null) {
                kbEntity.setSettlementCount((kbEntity.getSettlementCount() != null ? kbEntity.getSettlementCount() : 0) + 1);
                knowledgeBaseRepository.save(kbEntity);
            }
        } catch (Exception e) {
            log.warn("Failed to increment settlement count: {}", e.getMessage());
        }
    }

    /**
     * Extract a wiki graph (nodes and edges) from wikilinks in all wiki pages.
     */
    public Map<String, Object> getGraph(String tenantId, String kbId) {
        List<DocumentEntity> wikiDocs = documentRepository.findByTenantIdAndKbIdAndDocType(tenantId, kbId, DOC_TYPE_WIKI);

        List<Map<String, String>> nodes = new ArrayList<>();
        List<Map<String, String>> edges = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();

        for (DocumentEntity doc : wikiDocs) {
            // Skip index and log — they connect to everything and add noise
            String fn = doc.getFilename();
            if ("index.md".equals(fn) || "log.md".equals(fn)) continue;

            String title = filenameToTitle(fn);
            String nodeId = title.toLowerCase();
            if (nodeIds.add(nodeId)) {
                nodes.add(Map.of("id", nodeId, "label", title));
            }

            // Parse wikilinks from content
            String content = readWikiPage(tenantId, kbId, doc.getFilename());
            if (content != null) {
                List<String> links = extractWikilinks(content);
                for (String link : links) {
                    String targetId = link.toLowerCase();
                    if (nodeIds.add(targetId)) {
                        nodes.add(Map.of("id", targetId, "label", link));
                    }
                    edges.add(Map.of("source", nodeId, "target", targetId));
                }
            }
        }

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return graph;
    }

    /**
     * Ingest a URL: fetch HTML, extract text, store as a raw document, trigger processing.
     */
    public Map<String, Object> ingestUrl(String tenantId, String kbId, String url) {
        // 1. Fetch HTML
        String html;
        try {
            int maxRedirects = 3;
            HttpResponse<String> response = null;
            String currentUrl = url;
            for (int i = 0; i <= maxRedirects; i++) {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(currentUrl))
                        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9,zh-CN;q=0.8")
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();
                response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                int sc = response.statusCode();
                if (sc >= 300 && sc < 400) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location == null) break;
                    if (location.startsWith("/")) {
                        URI base = URI.create(currentUrl);
                        location = base.getScheme() + "://" + base.getHost() + location;
                    }
                    currentUrl = location;
                    continue;
                }
                break;
            }
            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP " + response.statusCode());
            }
            html = response.body();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch URL: " + e.getMessage(), e);
        }

        // 2. Extract title from <title> tag
        String title = "Untitled";
        java.util.regex.Matcher titleMatcher = java.util.regex.Pattern
                .compile("<title[^>]*>(.*?)</title>", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
        if (titleMatcher.find()) {
            title = titleMatcher.group(1).strip().replaceAll("\\s+", " ");
            if (title.length() > 200) title = title.substring(0, 200);
        }

        // 3. Extract text content: strip HTML tags, keep text
        String text = html
                .replaceAll("(?s)<script[^>]*>.*?</script>", "")
                .replaceAll("(?s)<style[^>]*>.*?</style>", "")
                .replaceAll("(?s)<nav[^>]*>.*?</nav>", "")
                .replaceAll("(?s)<header[^>]*>.*?</header>", "")
                .replaceAll("(?s)<footer[^>]*>.*?</footer>", "")
                .replaceAll("<br\\s*/?>", "\n")
                .replaceAll("</p>", "\n\n")
                .replaceAll("</h[1-6]>", "\n\n")
                .replaceAll("</li>", "\n")
                .replaceAll("<[^>]+>", "")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'")
                .replaceAll("\n{3,}", "\n\n")
                .strip();

        if (text.length() < 100) {
            throw new RuntimeException("Extracted content too short, the page may require JavaScript rendering or login");
        }

        // 4. Create document
        String markdown = "# " + title + "\n\n> Source: " + url + "\n\n" + text;
        String filename = title.replaceAll("[/\\\\:*?\"<>|]", "_") + ".md";
        String docId = "doc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        // Find KB to get databaseId
        var kb = knowledgeService.getKnowledgeBase(tenantId, kbId);
        String databaseId = kb.getDatabaseId();

        // Save document entity
        DocumentEntity doc = new DocumentEntity();
        doc.setId(docId);
        doc.setTenantId(tenantId);
        doc.setKbId(kbId);
        doc.setDatabaseId(databaseId);
        doc.setFilename(filename);
        doc.setDocType("raw");
        doc.setFormat("MARKDOWN");
        doc.setStatus(DocumentStatus.PENDING);
        doc.setTags(List.of("url-import"));
        doc.setMetadata(Map.of("source_url", url));

        String obsKey = "knowledge/" + tenantId + "/" + kbId + "/" + docId + "/" + filename;
        doc.setObsKey(obsKey);
        doc.setSizeBytes((long) markdown.getBytes(StandardCharsets.UTF_8).length);
        documentRepository.save(doc);

        // Upload to OBS
        uploadToObs(obsKey, markdown.getBytes(StandardCharsets.UTF_8), "text/markdown");

        // Also upload fulltext.md for ChunkService.getFulltext()
        String fulltextKey = "knowledge/" + tenantId + "/" + kbId + "/" + docId + "/fulltext.md";
        uploadToObs(fulltextKey, markdown.getBytes(StandardCharsets.UTF_8), "text/markdown");

        // Trigger document processing (parse → chunk → embed → summarize → wiki update)
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tenant_id", tenantId);
            params.put("kb_id", kbId);
            params.put("document_id", docId);
            params.put("database_id", databaseId);
            params.put("obs_key", obsKey);
            params.put("format", "MARKDOWN");
            params.put("filename", filename);
            params.put("database_connstr", "placeholder");
            params.put("embedding_api_url", props.getKnowledge().getEmbeddingApiUrl());
            params.put("embedding_api_key", props.getKnowledge().getEmbeddingApiKey());
            params.put("embedding_model", kb.getEmbeddingModel() != null
                    ? kb.getEmbeddingModel() : props.getKnowledge().getEmbeddingModel());
            kbWriteQueue.enqueueTask(databaseId, KbWriteTaskType.DOCUMENT_PARSE, params);
        } catch (Exception e) {
            log.warn("Failed to enqueue DOCUMENT_PARSE for URL import {}: {}", docId, e.getMessage());
        }

        log.info("URL ingested: {} → doc {} ({})", url, docId, filename);

        return Map.of("document_id", docId, "filename", filename, "status", "processing");
    }

    /**
     * Wiki chat — Query Router Agent.
     * Step 1: routes the question to relevant wiki pages and determines depth.
     * Step 2: reads wiki pages, optionally searches raw chunks, and generates an answer.
     */
    public Map<String, Object> chat(String tenantId, String kbId, String question,
                                     List<Map<String, String>> history) {
        // 1. Read index.md to get an overview of available wiki pages
        String indexContent = readWikiPage(tenantId, kbId, "index.md");
        if (indexContent == null) {
            indexContent = "# Wiki Index\n\nNo pages yet.\n";
        }
        if (indexContent.length() > MAX_INDEX_CHARS) {
            indexContent = indexContent.substring(0, MAX_INDEX_CHARS);
        }

        // 2. Routing: ask LLM which pages are relevant and whether this is simple or deep
        String routingPrompt = buildRoutingPrompt(indexContent, question);
        String routingResponse = callDeepSeek(routingPrompt);

        // 3. Parse routing response: {"relevant_pages": [...], "depth": "simple|deep"}
        List<String> relevantPages = new ArrayList<>();
        String depth = "simple";
        try {
            JsonNode routing = objectMapper.readTree(routingResponse);
            JsonNode pagesNode = routing.path("relevant_pages");
            if (pagesNode.isArray()) {
                for (JsonNode p : pagesNode) {
                    String pageName = p.asText("").trim();
                    if (!pageName.isBlank()) {
                        relevantPages.add(pageName);
                    }
                }
            }
            String depthVal = routing.path("depth").asText("simple").trim().toLowerCase();
            if ("deep".equals(depthVal)) {
                depth = "deep";
            }
        } catch (Exception e) {
            log.warn("Failed to parse routing response, falling back to defaults: {}", e.getMessage());
        }
        log.debug("Wiki chat routing: depth={}, pages={}", depth, relevantPages);

        // 4. Read relevant wiki pages
        StringBuilder wikiContext = new StringBuilder();
        List<String> sources = new ArrayList<>();
        for (String pageTitle : relevantPages) {
            String filename = titleToFilename(pageTitle);
            String pageContent = readWikiPage(tenantId, kbId, filename);
            if (pageContent != null && !pageContent.isBlank()) {
                wikiContext.append("### ").append(pageTitle).append("\n");
                wikiContext.append(pageContent, 0, Math.min(pageContent.length(), 4000));
                wikiContext.append("\n\n");
                sources.add(pageTitle);
            }
        }

        // 5. For deep questions, also search raw chunks
        StringBuilder rawContext = new StringBuilder();
        if ("deep".equals(depth)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> searchResult = knowledgeService.search(
                        tenantId, kbId, question, 10,
                        null, null, null, null, false, null);
                Object resultsObj = searchResult.get("results");
                if (resultsObj instanceof List<?> resultList) {
                    for (Object item : resultList) {
                        if (item instanceof Map<?, ?> resultMap) {
                            Object contentObj = resultMap.get("content");
                            if (contentObj instanceof String content && !content.isBlank()) {
                                rawContext.append(content, 0, Math.min(content.length(), 800));
                                rawContext.append("\n\n");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Raw chunk search failed during wiki chat: {}", e.getMessage());
            }
        }

        // 6. Build answer prompt and call LLM in free-text mode
        String answerPrompt = buildAnswerPrompt(question, history, wikiContext.toString(), rawContext.toString());
        String answer = callDeepSeekText(answerPrompt);

        // 7. Increment chat count
        try {
            KnowledgeBaseEntity kbEntity = knowledgeBaseRepository.findById(kbId).orElse(null);
            if (kbEntity != null) {
                kbEntity.setChatCount((kbEntity.getChatCount() != null ? kbEntity.getChatCount() : 0) + 1);
                knowledgeBaseRepository.save(kbEntity);
            }
        } catch (Exception e) {
            log.warn("Failed to increment chat count: {}", e.getMessage());
        }

        // 8. Return result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer != null ? answer : "");
        result.put("depth", depth);
        result.put("sources", sources);
        return result;
    }

    /**
     * Build the routing prompt that asks the LLM to identify relevant wiki pages and question depth.
     */
    private String buildRoutingPrompt(String indexContent, String question) {
        return """
                You are a query router for a wiki knowledge base.
                Given the wiki index below and a user question, output a JSON object identifying:
                1. Which wiki page titles are most relevant to the question (use the exact titles from the index).
                2. Whether the question is "simple" (answerable from wiki summaries/overviews) or "deep" (requires detailed document chunks).

                Wiki index:
                ---
                %s
                ---

                User question: %s

                Output ONLY valid JSON in this exact format (no markdown, no explanation):
                {"relevant_pages": ["Page Title 1", "Page Title 2"], "depth": "simple"}

                Rules:
                - relevant_pages: list of page titles from the index that are directly relevant; empty list if none.
                - depth: "simple" for factual or overview questions, "deep" for analytical, comparative, or detailed questions.
                """.formatted(indexContent, question);
    }

    /**
     * Build the answer prompt using wiki context, optional raw chunk context, question, and history.
     */
    private String buildAnswerPrompt(String question, List<Map<String, String>> history,
                                      String wikiContext, String rawContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful wiki assistant. Answer the user's question based on the provided context.\n\n");

        if (!wikiContext.isBlank()) {
            sb.append("## Wiki Pages\n\n");
            sb.append(wikiContext);
        }

        if (!rawContext.isBlank()) {
            sb.append("## Additional Document Excerpts\n\n");
            sb.append(rawContext);
        }

        if (wikiContext.isBlank() && rawContext.isBlank()) {
            sb.append("No relevant context found in the knowledge base.\n\n");
        }

        if (history != null && !history.isEmpty()) {
            sb.append("## Conversation History\n\n");
            for (Map<String, String> turn : history) {
                String role = turn.getOrDefault("role", "user");
                String content = turn.getOrDefault("content", "");
                sb.append(role.equals("assistant") ? "Assistant: " : "User: ");
                sb.append(content).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## Question\n\n");
        sb.append(question).append("\n\n");
        sb.append("""
                ## Instructions
                - Answer based on the wiki and document context above.
                - Use [[wikilink]] syntax to reference relevant wiki pages by their exact title.
                - Cite the source page(s) when making specific claims.
                - If the context does not contain enough information to answer, say so honestly.
                - Write in the same language as the question.
                - Use clear, concise Markdown formatting.
                """);

        return sb.toString();
    }

    /**
     * Call DeepSeek LLM for free-text (Markdown) response — no JSON mode.
     */
    private String callDeepSeekText(String prompt) {
        String apiKey = getWikiApiKey();
        String baseUrl = getWikiBaseUrl();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("Wiki/AI API key not configured, cannot run wiki agent");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", getModel());
        requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", 0.3);
        requestBody.put("max_tokens", 4096);
        // No response_format — free-text Markdown output

        try {
            String body = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("DeepSeek API returned " + response.statusCode()
                        + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content")
                    .asText("").trim();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("DeepSeek call failed: " + e.getMessage(), e);
        }
    }

    // ── Internal helpers ────────────────────────────────────────────

    /**
     * Apply wiki changes from the LLM response: write wiki pages, update index, append log.
     */
    private void applyWikiChanges(String tenantId, String kbId, JsonNode response,
                                   String currentIndex) {
        JsonNode wikiPages = response.path("wiki_pages");
        JsonNode indexUpdates = response.path("index_updates");
        String logEntry = response.path("log_entry").asText("");

        if (!wikiPages.isArray() || wikiPages.isEmpty()) {
            log.info("No wiki pages to create/update for KB {}", kbId);
            return;
        }

        int created = 0, updated = 0;

        for (JsonNode page : wikiPages) {
            String title = page.path("title").asText("");
            String action = page.path("action").asText("create");
            String content = page.path("content").asText("");

            if (title.isBlank() || content.isBlank()) continue;

            String filename = titleToFilename(title);
            writeWikiDocument(tenantId, kbId, filename, title, content);

            // Enqueue DOCUMENT_PARSE so wiki page gets chunked and embedded
            triggerDocumentParse(tenantId, kbId, filename);

            if ("update".equals(action)) {
                updated++;
            } else {
                created++;
            }
        }

        // Update index.md
        if (indexUpdates.isArray() && !indexUpdates.isEmpty()) {
            String newIndex = buildUpdatedIndex(currentIndex, indexUpdates);
            writeWikiDocument(tenantId, kbId, "index.md", "Wiki Index", newIndex);
        }

        // Append to log.md
        if (!logEntry.isBlank()) {
            appendToLog(tenantId, kbId, "[文档导入] " + logEntry);
        }

        log.info("Wiki update complete for KB {}: {} created, {} updated", kbId, created, updated);
    }

    /**
     * Write or update a wiki document entity and upload content to OBS.
     */
    private void writeWikiDocument(String tenantId, String kbId, String filename,
                                    String title, String content) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenantId)
                .orElse(null);
        if (kb == null) {
            log.error("Knowledge base not found: {} for tenant {}", kbId, tenantId);
            return;
        }

        // Find or create document entity
        Optional<DocumentEntity> existing = documentRepository.findByTypeAndFilename(
                tenantId, kbId, DOC_TYPE_WIKI, filename);

        DocumentEntity doc;
        if (existing.isPresent()) {
            doc = existing.get();
        } else {
            doc = new DocumentEntity();
            doc.setTenantId(tenantId);
            doc.setDatabaseId(kb.getDatabaseId());
            doc.setKbId(kbId);
            doc.setFilename(filename);
            doc.setFormat("md");
            doc.setDocType(DOC_TYPE_WIKI);
            doc.setStatus(DocumentStatus.READY);
            doc.setTags(List.of("wiki"));
            doc.setMetadata(new LinkedHashMap<>(Map.of("title", title)));
        }

        // Save first to trigger @PrePersist and generate id
        doc.setSizeBytes((long) content.getBytes(StandardCharsets.UTF_8).length);
        documentRepository.save(doc);

        // Now doc.getId() is guaranteed to be set
        String obsKey = "knowledge/" + tenantId + "/" + kbId + "/" + doc.getId() + "/" + filename;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        uploadToObs(obsKey, bytes, "text/markdown; charset=utf-8");

        // Also write as fulltext.md so ChunkService.getFulltext() can find it
        String fulltextKey = "knowledge/" + tenantId + "/" + kbId + "/" + doc.getId() + "/fulltext.md";
        uploadToObs(fulltextKey, bytes, "text/markdown; charset=utf-8");

        doc.setObsKey(obsKey);
        documentRepository.save(doc);

        log.debug("Wiki document written: {} ({} bytes)", filename, bytes.length);
    }

    /**
     * Trigger document parsing (chunking + embedding) for a wiki page.
     * Finds the document entity by filename and enqueues a DOCUMENT_PARSE task.
     */
    private void triggerDocumentParse(String tenantId, String kbId, String filename) {
        Optional<DocumentEntity> docOpt = documentRepository.findByTypeAndFilename(
                tenantId, kbId, DOC_TYPE_WIKI, filename);
        if (docOpt.isEmpty()) return;

        DocumentEntity doc = docOpt.get();
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findByIdAndTenantId(kbId, tenantId)
                .orElse(null);
        if (kb == null) return;

        doc.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(doc);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("document_id", doc.getId());
        params.put("tenant_id", tenantId);
        params.put("kb_id", kbId);
        params.put("obs_key", doc.getObsKey());
        params.put("format", "MARKDOWN");
        params.put("filename", filename);
        params.put("database_connstr", "placeholder");
        params.put("embedding_api_url", props.getKnowledge().getEmbeddingApiUrl());
        params.put("embedding_api_key", props.getKnowledge().getEmbeddingApiKey());
        params.put("embedding_model", kb.getEmbeddingModel() != null
                ? kb.getEmbeddingModel() : props.getKnowledge().getEmbeddingModel());

        kbWriteQueue.enqueueTask(kb.getDatabaseId(), KbWriteTaskType.DOCUMENT_PARSE, params);
        log.debug("Enqueued DOCUMENT_PARSE for wiki page: {}", filename);
    }

    /**
     * Build an updated index.md by merging new entries into the existing index.
     */
    private String buildUpdatedIndex(String currentIndex, JsonNode indexUpdates) {
        StringBuilder sb = new StringBuilder();
        // Keep the header if it exists
        if (currentIndex.startsWith("# ")) {
            int newlinePos = currentIndex.indexOf('\n');
            if (newlinePos > 0) {
                sb.append(currentIndex, 0, newlinePos + 1);
            }
        } else {
            sb.append("# Wiki Index\n");
        }
        sb.append("\n");

        // Collect existing entries (title -> summary)
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        for (String line : currentIndex.split("\n")) {
            if (line.startsWith("- [[")) {
                int end = line.indexOf("]]");
                if (end > 4) {
                    String title = line.substring(4, end);
                    String summary = "";
                    int dashPos = line.indexOf(" — ", end);
                    if (dashPos > 0) {
                        summary = line.substring(dashPos + 3).trim();
                    }
                    entries.put(title, summary);
                }
            }
        }

        // Merge new entries
        for (JsonNode update : indexUpdates) {
            String title = update.path("title").asText("");
            String summary = update.path("summary").asText("");
            if (!title.isBlank()) {
                entries.put(title, summary);
            }
        }

        // Write sorted entries
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            sb.append("- [[").append(entry.getKey()).append("]]");
            if (!entry.getValue().isBlank()) {
                sb.append(" — ").append(entry.getValue());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Append an entry to log.md.
     */
    private void appendToLog(String tenantId, String kbId, String entry) {
        String currentLog = readWikiPage(tenantId, kbId, "log.md");
        if (currentLog == null) {
            currentLog = "# Wiki Change Log\n\n";
        }

        String timestamp = LOG_TS_FMT.format(Instant.now());
        String newLog = currentLog + "- " + timestamp + " — " + entry + "\n";

        writeWikiDocument(tenantId, kbId, "log.md", "Wiki Change Log", newLog);
    }

    /**
     * Read a wiki page content from OBS by filename.
     */
    private String readWikiPage(String tenantId, String kbId, String filename) {
        Optional<DocumentEntity> docOpt = documentRepository.findByTypeAndFilename(
                tenantId, kbId, DOC_TYPE_WIKI, filename);
        if (docOpt.isEmpty()) return null;

        String obsKey = docOpt.get().getObsKey();
        if (obsKey == null) return null;

        try (S3Client s3 = buildS3Client()) {
            var resp = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(props.getObs().getBucket())
                    .key(obsKey)
                    .build());
            return new String(resp.asByteArray(), StandardCharsets.UTF_8);
        } catch (NoSuchKeyException e) {
            return null;
        } catch (Exception e) {
            log.warn("Failed to read wiki page {} from OBS: {}", filename, e.getMessage());
            return null;
        }
    }

    /**
     * Call DeepSeek LLM with JSON response format.
     */
    private String callDeepSeek(String prompt) {
        String apiKey = getWikiApiKey();
        String baseUrl = getWikiBaseUrl();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("Wiki/AI API key not configured, cannot run wiki agent");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", getModel());
        requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", 0.0);
        requestBody.put("max_tokens", 4096);
        requestBody.put("response_format", Map.of("type", "json_object"));

        try {
            String body = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("DeepSeek API returned " + response.statusCode()
                        + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content")
                    .asText("").trim();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("DeepSeek call failed: " + e.getMessage(), e);
        }
    }

    private String getWikiApiKey() {
        String wikiKey = props.getWiki().getApiKey();
        if (wikiKey != null && !wikiKey.isBlank()) return wikiKey;
        return props.getAi().getApiKey();
    }

    private String getWikiBaseUrl() {
        String wikiUrl = props.getWiki().getBaseUrl();
        if (wikiUrl != null && !wikiUrl.isBlank()) return wikiUrl;
        return "https://api.deepseek.com/v1";
    }

    /**
     * Upload content bytes to OBS.
     */
    private void uploadToObs(String obsKey, byte[] bytes, String contentType) {
        try (S3Client s3 = buildS3Client()) {
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(props.getObs().getBucket())
                    .key(obsKey)
                    .contentType(contentType)
                    .contentLength((long) bytes.length)
                    .build();
            s3.putObject(req, RequestBody.fromBytes(bytes));
        }
    }

    private S3Client buildS3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(props.getObs().getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
                .region(Region.of("cn-north-4"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
                .build();
    }

    /**
     * Extract [[wikilinks]] from markdown content.
     */
    static List<String> extractWikilinks(String content) {
        List<String> links = new ArrayList<>();
        int pos = 0;
        while (pos < content.length()) {
            int start = content.indexOf("[[", pos);
            if (start < 0) break;
            int end = content.indexOf("]]", start + 2);
            if (end < 0) break;
            String link = content.substring(start + 2, end).trim();
            if (!link.isBlank() && !link.contains("\n")) {
                links.add(link);
            }
            pos = end + 2;
        }
        return links;
    }

    /**
     * Streaming wiki chat — returns SSE events for real-time answer display.
     * Step 1 (routing) runs synchronously, then Step 2 streams the answer.
     */
    public void chatStream(String tenantId, String kbId, String question,
                           List<Map<String, String>> history,
                           java.util.function.Consumer<String> onEvent) {
        // 1. Read index.md to get an overview of available wiki pages
        String indexContent = readWikiPage(tenantId, kbId, "index.md");
        if (indexContent == null) {
            indexContent = "# Wiki Index\n\nNo pages yet.\n";
        }
        if (indexContent.length() > MAX_INDEX_CHARS) {
            indexContent = indexContent.substring(0, MAX_INDEX_CHARS);
        }

        // 2. Routing: ask LLM which pages are relevant and whether this is simple or deep
        String routingPrompt = buildRoutingPrompt(indexContent, question);
        String routingResponse = callDeepSeek(routingPrompt);

        // 3. Parse routing response
        List<String> relevantPages = new ArrayList<>();
        String depth = "simple";
        try {
            JsonNode routing = objectMapper.readTree(routingResponse);
            JsonNode pagesNode = routing.path("relevant_pages");
            if (pagesNode.isArray()) {
                for (JsonNode p : pagesNode) {
                    String pageName = p.asText("").trim();
                    if (!pageName.isBlank()) {
                        relevantPages.add(pageName);
                    }
                }
            }
            String depthVal = routing.path("depth").asText("simple").trim().toLowerCase();
            if ("deep".equals(depthVal)) {
                depth = "deep";
            }
        } catch (Exception e) {
            log.warn("Failed to parse routing response, falling back to defaults: {}", e.getMessage());
        }
        log.debug("Wiki chat stream routing: depth={}, pages={}", depth, relevantPages);

        // 4. Read relevant wiki pages
        StringBuilder wikiContext = new StringBuilder();
        List<String> sources = new ArrayList<>();
        for (String pageTitle : relevantPages) {
            String filename = titleToFilename(pageTitle);
            String pageContent = readWikiPage(tenantId, kbId, filename);
            if (pageContent != null && !pageContent.isBlank()) {
                wikiContext.append("### ").append(pageTitle).append("\n");
                wikiContext.append(pageContent, 0, Math.min(pageContent.length(), 4000));
                wikiContext.append("\n\n");
                sources.add(pageTitle);
            }
        }

        // 5. For deep questions, also search raw chunks
        StringBuilder rawContext = new StringBuilder();
        if ("deep".equals(depth)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> searchResult = knowledgeService.search(
                        tenantId, kbId, question, 10,
                        null, null, null, null, false, null);
                Object resultsObj = searchResult.get("results");
                if (resultsObj instanceof List<?> resultList) {
                    for (Object item : resultList) {
                        if (item instanceof Map<?, ?> resultMap) {
                            Object contentObj = resultMap.get("content");
                            if (contentObj instanceof String content && !content.isBlank()) {
                                rawContext.append(content, 0, Math.min(content.length(), 800));
                                rawContext.append("\n\n");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Raw chunk search failed during wiki chat stream: {}", e.getMessage());
            }
        }

        // 6. Send metadata event first
        try {
            String metaJson = objectMapper.writeValueAsString(
                    Map.of("type", "meta", "depth", depth, "sources", sources));
            onEvent.accept(metaJson);
        } catch (Exception e) {
            log.warn("Failed to send meta event: {}", e.getMessage());
        }

        // 7. Build answer prompt and stream the answer
        String answerPrompt = buildAnswerPrompt(question, history, wikiContext.toString(), rawContext.toString());
        callDeepSeekStream(answerPrompt, chunk -> {
            try {
                String chunkJson = objectMapper.writeValueAsString(
                        Map.of("type", "chunk", "content", chunk));
                onEvent.accept(chunkJson);
            } catch (Exception e) {
                log.warn("Failed to send chunk event: {}", e.getMessage());
            }
        });
    }

    /**
     * Call DeepSeek LLM with streaming (SSE). Emits content chunks via onChunk callback.
     */
    private void callDeepSeekStream(String prompt,
                                     java.util.function.Consumer<String> onChunk) {
        String apiKey = getWikiApiKey();
        String baseUrl = getWikiBaseUrl();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("Wiki/AI API key not configured, cannot run wiki agent");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", getModel());
        requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("max_tokens", 4096);
        requestBody.put("temperature", 0.3);
        requestBody.put("stream", true);

        try {
            String body = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                byte[] errBytes = response.body().readAllBytes();
                throw new RuntimeException("DeepSeek stream API returned " + response.statusCode()
                        + ": " + new String(errBytes, StandardCharsets.UTF_8));
            }

            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        try {
                            JsonNode node = objectMapper.readTree(data);
                            String content = node.path("choices").path(0)
                                    .path("delta").path("content").asText("");
                            if (!content.isEmpty()) {
                                onChunk.accept(content);
                            }
                        } catch (Exception e) {
                            // skip malformed chunks
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM stream failed: " + e.getMessage(), e);
        }
    }

    /**
     * Convert a wiki page title to a filename: "Database Sharding" -> "database-sharding.md"
     */
    static String titleToFilename(String title) {
        return title.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "-")
                .replaceAll("^-+|-+$", "")
                + ".md";
    }

    /**
     * Convert a filename back to a display title: "database-sharding.md" -> "database-sharding"
     */
    static String filenameToTitle(String filename) {
        if (filename.endsWith(".md")) {
            return filename.substring(0, filename.length() - 3);
        }
        return filename;
    }
}
