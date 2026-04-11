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

    private static final String DEEPSEEK_MODEL = "deepseek-v3.2";
    private static final int MAX_FULLTEXT_CHARS = 28_000;
    private static final int MAX_INDEX_CHARS = 8_000;
    private static final String DOC_TYPE_WIKI = "wiki";
    private static final DateTimeFormatter LOG_TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    /**
     * Legacy curate prompt kept ONLY for {@link #fixLintIssues}, which still uses
     * a single-shot LLM call. All other ingest/curate flows have moved to the
     * external lakeon-wiki-agent service (see {@link WikiAgentClient}).
     */
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
            7. Write in Simplified Chinese (简体中文) regardless of the source content language. Translate any existing pages that are not in Chinese.
            8. DELETE pages that cover generic, widely-known concepts not specific to this knowledge base (e.g. "AI 产品设计", "对话式 AI", "数据安全"). Only keep pages for concepts that are central and unique to what this KB is about. Add them to the "delete_pages" array.

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

    private static final String DEFAULT_ROUTING_PROMPT = """
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
            """;

    private static final String DEFAULT_ANSWER_PROMPT = """
            You are a helpful wiki assistant. Answer the user's question based on the provided context.
            """;

    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ChunkService chunkService;
    private final DocumentRepository documentRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KbWriteQueue kbWriteQueue;
    private final KnowledgeService knowledgeService;
    private final WikiRunLogRepository wikiRunLogRepository;
    private final KbAccessService kbAccessService;

    public WikiService(LakeonProperties props,
                       ObjectMapper objectMapper,
                       ChunkService chunkService,
                       DocumentRepository documentRepository,
                       KnowledgeBaseRepository knowledgeBaseRepository,
                       KbWriteQueue kbWriteQueue,
                       KnowledgeService knowledgeService,
                       WikiRunLogRepository wikiRunLogRepository,
                       KbAccessService kbAccessService) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.chunkService = chunkService;
        this.documentRepository = documentRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.kbWriteQueue = kbWriteQueue;
        this.knowledgeService = knowledgeService;
        this.wikiRunLogRepository = wikiRunLogRepository;
        this.kbAccessService = kbAccessService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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
     * Returns the configured chat routing prompt, or the default if not set.
     */
    public String getChatRoutingPrompt() {
        String custom = props.getWiki() != null ? props.getWiki().getChatRoutingPrompt() : null;
        if (custom != null && !custom.isBlank()) return custom;
        return DEFAULT_ROUTING_PROMPT;
    }

    /**
     * Returns the configured chat answer prompt, or the default if not set.
     */
    public String getChatAnswerPrompt() {
        String custom = props.getWiki() != null ? props.getWiki().getChatAnswerPrompt() : null;
        if (custom != null && !custom.isBlank()) return custom;
        return DEFAULT_ANSWER_PROMPT;
    }

    /**
     * Rebuild all wiki pages: delete existing wiki docs and re-enqueue WIKI_UPDATE for each raw doc.
     * Returns the number of deleted wiki pages.
     */
    public int rebuildWiki(String tenantId, String kbId) {
        // Delete all doc_type=wiki documents for this KB
        List<DocumentEntity> wikiDocs = documentRepository.findByTenantIdAndKbIdAndDocType(tenantId, kbId, DOC_TYPE_WIKI);
        int deleted = wikiDocs.size();
        for (DocumentEntity doc : wikiDocs) {
            documentRepository.delete(doc);
        }

        // For each raw+READY document, enqueue WIKI_UPDATE
        List<DocumentEntity> rawDocs = documentRepository.findByTenantIdAndKbIdAndDocType(tenantId, kbId, "raw");
        var kb = knowledgeBaseRepository.findById(kbId).orElse(null);
        String databaseId = kb != null ? kb.getDatabaseId() : null;

        for (DocumentEntity rawDoc : rawDocs) {
            if (rawDoc.getStatus() == DocumentStatus.READY && databaseId != null) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("tenant_id", tenantId);
                params.put("kb_id", kbId);
                params.put("document_id", rawDoc.getId());
                params.put("database_id", databaseId);
                kbWriteQueue.enqueueTask(databaseId, KbWriteTaskType.WIKI_UPDATE, params);
            }
        }

        log.info("Rebuild wiki for KB {}: deleted {} wiki pages, enqueued {} raw docs", kbId, deleted, rawDocs.size());
        return deleted;
    }

    /**
     * Test LLM connection by sending a simple prompt.
     * Returns the response string, or throws on failure.
     */
    public String testConnection() {
        return callDeepSeekText("Say 'OK' in one word.");
    }

    /**
     * Use LLM to extract key points from document fulltext in Chinese.
     */
    public List<String> extractKeyPointsWithLlm(String fulltext) {
        if (fulltext.length() > 12000) {
            fulltext = fulltext.substring(0, 12000);
        }
        String prompt = "请从以下文档内容中提取 3-6 个核心要点，用简体中文输出。" +
                "每个要点一行，不要编号，不要 bullet，直接写内容。要点应该是独立的、有信息量的句子。\n\n" +
                "文档内容：\n" + fulltext;
        String response = callDeepSeekText(prompt);
        if (response == null || response.isBlank()) {
            return List.of();
        }
        List<String> points = new ArrayList<>();
        for (String line : response.split("\n")) {
            line = line.trim().replaceFirst("^[-*•]\\s*", "").replaceFirst("^\\d+[.)、]\\s*", "");
            if (line.length() > 5) {
                points.add(line);
            }
        }
        return points.size() > 8 ? points.subList(0, 8) : points;
    }

    /**
     * Save a chat response as a wiki page.
     */
    public void saveResponse(String tenantId, String kbId, String title, String content) {
        KnowledgeBaseEntity kbAccess = kbAccessService.getKbWithAccess(kbId, tenantId);
        tenantId = kbAccess.getTenantId();
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
        KnowledgeBaseEntity kb = kbAccessService.getKbWithAccess(kbId, tenantId);
        tenantId = kb.getTenantId(); // resolve to KB owner for internal queries
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
     * Falls back to Jina Reader for JavaScript-rendered pages.
     */
    public Map<String, Object> ingestUrl(String tenantId, String kbId, String url) {
        return ingestUrl(tenantId, kbId, url, null, null);
    }

    public Map<String, Object> ingestUrl(String tenantId, String kbId, String url,
                                          String prefetchedTitle, String prefetchedContent) {
        KnowledgeBaseEntity kbAccess = kbAccessService.getKbWithAccess(kbId, tenantId);
        tenantId = kbAccess.getTenantId();
        String text;
        String title;

        if (prefetchedContent != null && !prefetchedContent.isBlank()) {
            // Content was fetched by the browser (avoids server-side network restrictions)
            text = prefetchedContent;
            title = (prefetchedTitle != null && !prefetchedTitle.isBlank()) ? prefetchedTitle : "Untitled";
            log.info("URL ingest for {} using browser-prefetched content: {} chars", url, text.length());
        } else {
            // 1. Try direct server-side fetch first
            text = null;
            title = "Untitled";
            boolean usedJina = false;

            try {
                String[] extracted = fetchAndExtract(url);
                title = extracted[0];
                text = extracted[1];
            } catch (Exception e) {
                log.warn("Direct fetch failed for {}: {}", url, e.getMessage());
            }

            // 2. Fallback to Jina Reader if content is too short or fetch failed
            if (text == null || text.length() < 200) {
                log.info("Direct fetch insufficient for {}, trying Jina Reader", url);
                try {
                    String jinaUrl = "https://r.jina.ai/" + url;
                    HttpRequest jinaReq = HttpRequest.newBuilder()
                            .uri(URI.create(jinaUrl))
                            .header("User-Agent", "Mozilla/5.0")
                            .header("Accept", "text/plain,text/markdown,*/*")
                            .timeout(Duration.ofSeconds(45))
                            .GET()
                            .build();
                    HttpResponse<String> jinaResp = httpClient.send(jinaReq,
                            HttpResponse.BodyHandlers.ofString());
                    if (jinaResp.statusCode() == 200 && jinaResp.body().length() > 200) {
                        String body = jinaResp.body();
                        // Jina prepends metadata lines like "Title: ...\nURL Source: ...\n"
                        java.util.regex.Matcher tm = java.util.regex.Pattern
                                .compile("(?m)^Title:\\s*(.+)$").matcher(body);
                        if (tm.find()) title = tm.group(1).strip();
                        text = body;
                        usedJina = true;
                        log.info("Jina Reader succeeded for {}: {} chars", url, text.length());
                    }
                } catch (Exception e) {
                    log.warn("Jina Reader also failed for {}: {}", url, e.getMessage());
                }
            }

            if (text == null || text.length() < 100) {
                throw new RuntimeException("Failed to fetch URL: page content is too short or requires login.");
            }

            log.info("URL ingest for {}: {} chars, via {}", url, text.length(), usedJina ? "Jina" : "direct");
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
     * Fetch a URL and extract title + plain text from the HTML response.
     * Returns String[2]: [0]=title, [1]=plain text
     */
    private String[] fetchAndExtract(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (compatible; LakeonBot/1.0)")
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode());
        }
        String html = resp.body();

        // Extract title from <title> tag
        String title = "Untitled";
        java.util.regex.Matcher tm = java.util.regex.Pattern
                .compile("(?i)<title[^>]*>([^<]+)</title>").matcher(html);
        if (tm.find()) {
            title = tm.group(1).strip().replaceAll("\\s+", " ");
        }

        // Strip scripts, styles, then all HTML tags
        String text = html
                .replaceAll("(?si)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?si)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("(\\r?\\n[ \\t]*){3,}", "\n\n")
                .strip();

        return new String[]{title, text};
    }

    /**
     * Wiki chat — Query Router Agent.
     * Step 1: routes the question to relevant wiki pages and determines depth.
     * Step 2: reads wiki pages, optionally searches raw chunks, and generates an answer.
     */
    public Map<String, Object> chat(String tenantId, String kbId, String question,
                                     List<Map<String, String>> history) {
        KnowledgeBaseEntity kbAccess = kbAccessService.getKbWithAccess(kbId, tenantId);
        tenantId = kbAccess.getTenantId();
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
        return getChatRoutingPrompt().formatted(indexContent, question);
    }

    /**
     * Build the answer prompt using wiki context, optional raw chunk context, question, and history.
     */
    private String buildAnswerPrompt(String question, List<Map<String, String>> history,
                                      String wikiContext, String rawContext) {
        StringBuilder sb = new StringBuilder();
        sb.append(getChatAnswerPrompt().trim()).append("\n\n");

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
     * Write a WikiRunLog entry for operational history tracking.
     */
    private void writeRunLog(String tenantId, String kbId, String runType,
                              String triggerDoc, int created, int updated, int deleted,
                              long startMs, String error) {
        try {
            WikiRunLogEntity runLog = new WikiRunLogEntity();
            runLog.setId(UUID.randomUUID().toString().replace("-", "").substring(0, 32));
            runLog.setTenantId(tenantId);
            runLog.setKbId(kbId);
            runLog.setRunType(runType);
            runLog.setTriggerDoc(triggerDoc);
            runLog.setPagesCreated(created);
            runLog.setPagesUpdated(updated);
            runLog.setPagesDeleted(deleted);
            runLog.setDurationMs(System.currentTimeMillis() - startMs);
            runLog.setStatus(error == null ? "success" : "error");
            runLog.setErrorMessage(error != null && error.length() > 1024 ? error.substring(0, 1024) : error);
            runLog.setCreatedAt(Instant.now());
            wikiRunLogRepository.save(runLog);
        } catch (Exception e) {
            log.warn("Failed to write wiki run log: {}", e.getMessage());
        }
    }

    /**
     * Write or update a wiki document entity and upload content to OBS.
     */
    void writeWikiDocument(String tenantId, String kbId, String filename,
                                    String title, String content) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId).orElse(null);
        if (kb == null) {
            log.error("Knowledge base not found: {}", kbId);
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
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId).orElse(null);
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
     * Append an entry to log.md.
     */
    void appendToLog(String tenantId, String kbId, String entry) {
        String currentLog = readWikiPage(tenantId, kbId, "log.md");
        if (currentLog == null) {
            currentLog = "# Wiki Change Log\n\n";
        }

        String timestamp = LOG_TS_FMT.format(Instant.now());
        String newLog = currentLog + "- " + timestamp + " — " + entry + "\n";

        writeWikiDocument(tenantId, kbId, "log.md", "Wiki Change Log", newLog);
    }

    // TODO(task-5.1): after WikiService shrinks, extract {readWikiPage, writeWikiDocument, appendToLog}
    //  into a dedicated WikiPageIO helper so visibility can become truly private.
    /**
     * Read a wiki page content from OBS by filename.
     */
    String readWikiPage(String tenantId, String kbId, String filename) {
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
            return stripMarkdownFence(root.path("choices").path(0).path("message").path("content")
                    .asText("").trim());
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
        return "https://api.modelarts-maas.com/openai/v1";
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
    /** Strip markdown code fences (```json ... ```) that some LLMs wrap around JSON output. */
    private static String stripMarkdownFence(String text) {
        if (text != null && text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }

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
        KnowledgeBaseEntity kbAccess = kbAccessService.getKbWithAccess(kbId, tenantId);
        tenantId = kbAccess.getTenantId();
        // 1. Get all wiki page entities (single DB query, fast)
        List<DocumentEntity> wikiDocs = documentRepository.findByTenantIdAndKbIdAndDocType(
                tenantId, kbId, DOC_TYPE_WIKI);

        // 2. Determine depth heuristically (no LLM call needed)
        String depth = isDeepQuestion(question) ? "deep" : "simple";

        // 3. Select relevant pages via keyword matching (no LLM routing call)
        List<DocumentEntity> relevantDocs = selectRelevantPages(wikiDocs, question);

        // 4. Load page contents from OBS
        StringBuilder wikiContext = new StringBuilder();
        List<String> sources = new ArrayList<>();
        int totalChars = 0;
        for (DocumentEntity doc : relevantDocs) {
            if (totalChars >= 20000) break;
            String pageContent = readWikiPage(tenantId, kbId, doc.getFilename());
            if (pageContent != null && !pageContent.isBlank()) {
                String title = doc.getFilename().replace(".md", "");
                wikiContext.append("### ").append(title).append("\n");
                int take = Math.min(pageContent.length(), 4000);
                wikiContext.append(pageContent, 0, take).append("\n\n");
                sources.add(title);
                totalChars += take;
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

        // 6. Send metadata event immediately (no LLM wait)
        try {
            String metaJson = objectMapper.writeValueAsString(
                    Map.of("type", "meta", "depth", depth, "sources", sources));
            onEvent.accept(metaJson);
        } catch (Exception e) {
            log.warn("Failed to send meta event: {}", e.getMessage());
        }

        // 7. Stream the answer directly
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
     * Determine if a question requires deep analysis based on keywords.
     */
    private boolean isDeepQuestion(String question) {
        String q = question.toLowerCase();
        return q.contains("为什么") || q.contains("原因") || q.contains("分析") ||
               q.contains("比较") || q.contains("对比") || q.contains("详细") ||
               q.contains("深入") || q.contains("explain") || q.contains("why") ||
               q.contains("compare") || q.contains("analyze") || q.contains("difference");
    }

    /**
     * Select relevant wiki pages using keyword matching (no LLM needed).
     * If few pages, return all. Otherwise score by keyword overlap with question.
     */
    private List<DocumentEntity> selectRelevantPages(List<DocumentEntity> allDocs, String question) {
        // Filter out index and log pages
        List<DocumentEntity> pages = allDocs.stream()
                .filter(d -> !"index.md".equals(d.getFilename()) && !"log.md".equals(d.getFilename()))
                .collect(java.util.stream.Collectors.toList());

        // If 5 or fewer pages, load all (context is small enough)
        if (pages.size() <= 5) return pages;

        // Score pages by keyword overlap between question and filename/title
        String[] words = question.toLowerCase().split("[\\s\\p{Punct}]+");
        java.util.Map<DocumentEntity, Integer> scores = new java.util.LinkedHashMap<>();
        for (DocumentEntity doc : pages) {
            String title = doc.getFilename().replace(".md", "").toLowerCase();
            int score = 0;
            for (String word : words) {
                if (word.length() >= 2 && title.contains(word)) {
                    score += word.length(); // longer word match = higher score
                }
            }
            scores.put(doc, score);
        }

        // Return top 5 by score; if all score 0, return first 5
        return scores.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
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

    // ── Wiki Lint ────────────────────────────────────────────────────

    /**
     * Run lint checks on all wiki pages: rule-based (language, orphan, broken_link)
     * followed by LLM-based analysis (contradiction, stale, missing_link).
     */
    public Map<String, Object> runLint(String tenantId, String kbId) {
        KnowledgeBaseEntity kb = kbAccessService.getKbWithAccess(kbId, tenantId);
        tenantId = kb.getTenantId();

        List<DocumentEntity> wikiDocs = documentRepository.findByTenantIdAndKbIdAndDocType(
                tenantId, kbId, DOC_TYPE_WIKI);

        // Load all content pages (skip index.md, log.md)
        Map<String, String> pageContents = new LinkedHashMap<>(); // title -> content
        Set<String> pageTitles = new HashSet<>(); // normalized titles for existence check
        for (DocumentEntity doc : wikiDocs) {
            String fn = doc.getFilename();
            if ("index.md".equals(fn) || "log.md".equals(fn)) continue;
            String title = filenameToTitle(fn);
            String content = readWikiPage(tenantId, kbId, fn);
            if (content != null) {
                pageContents.put(title, content);
                pageTitles.add(normalizeLinkTitle(title));
            }
        }

        List<Map<String, Object>> issues = new ArrayList<>();
        Map<String, Integer> summary = new LinkedHashMap<>();
        String[] categories = {"language", "orphan", "broken_link", "contradiction", "stale", "missing_link"};
        for (String c : categories) summary.put(c, 0);

        // 1. Language check
        for (Map.Entry<String, String> entry : pageContents.entrySet()) {
            String title = entry.getKey();
            String content = entry.getValue();
            String stripped = stripMarkdown(content);
            if (stripped.isEmpty()) continue;
            double chineseRatio = calcChineseRatio(stripped);
            if (chineseRatio < 0.3) {
                issues.add(lintIssue("language", "error", title,
                        "全页非中文内容，中文比例仅 " + Math.round(chineseRatio * 100) + "%", List.of()));
                summary.merge("language", 1, Integer::sum);
            } else if (chineseRatio < 0.7) {
                issues.add(lintIssue("language", "warning", title,
                        "部分非中文内容，中文比例 " + Math.round(chineseRatio * 100) + "%", List.of()));
                summary.merge("language", 1, Integer::sum);
            }
        }

        // 2. Orphan check — build inbound link map
        Map<String, Set<String>> inboundLinks = new HashMap<>();
        for (String title : pageContents.keySet()) {
            inboundLinks.put(normalizeLinkTitle(title), new HashSet<>());
        }
        for (Map.Entry<String, String> entry : pageContents.entrySet()) {
            String sourceTitle = entry.getKey();
            List<String> links = extractWikilinks(entry.getValue());
            for (String link : links) {
                String targetKey = normalizeLinkTitle(link);
                if (inboundLinks.containsKey(targetKey)) {
                    inboundLinks.get(targetKey).add(sourceTitle);
                }
            }
        }
        for (Map.Entry<String, Set<String>> entry : inboundLinks.entrySet()) {
            if (entry.getValue().isEmpty()) {
                // Find original-case title
                String title = entry.getKey();
                for (String t : pageContents.keySet()) {
                    if (normalizeLinkTitle(t).equals(entry.getKey())) { title = t; break; }
                }
                issues.add(lintIssue("orphan", "warning", title,
                        "孤立页面：没有任何其他页面链接到此页", List.of()));
                summary.merge("orphan", 1, Integer::sum);
            }
        }

        // 3. Broken link check
        Map<String, Set<String>> brokenLinks = new LinkedHashMap<>(); // target -> set of source pages
        for (Map.Entry<String, String> entry : pageContents.entrySet()) {
            String sourceTitle = entry.getKey();
            List<String> links = extractWikilinks(entry.getValue());
            for (String link : links) {
                if (!pageTitles.contains(normalizeLinkTitle(link))) {
                    brokenLinks.computeIfAbsent(link, k -> new LinkedHashSet<>()).add(sourceTitle);
                }
            }
        }
        for (Map.Entry<String, Set<String>> entry : brokenLinks.entrySet()) {
            String target = entry.getKey();
            Set<String> sources = entry.getValue();
            issues.add(lintIssue("broken_link", "error", target,
                    "断链：" + sources.size() + " 个页面引用了不存在的 [[" + target + "]]",
                    new ArrayList<>(sources)));
            summary.merge("broken_link", 1, Integer::sum);
        }

        // 4. LLM-based analysis
        try {
            List<Map<String, Object>> llmIssues = runLlmLintAnalysis(pageContents);
            for (Map<String, Object> issue : llmIssues) {
                String cat = (String) issue.get("category");
                issues.add(issue);
                summary.merge(cat, 1, Integer::sum);
            }
        } catch (Exception e) {
            log.warn("LLM lint analysis failed for KB {}: {}", kbId, e.getMessage());
        }

        int total = summary.values().stream().mapToInt(Integer::intValue).sum();
        summary.put("total", total);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issues", issues);
        result.put("summary", summary);
        result.put("checked_at", Instant.now().toString());
        return result;
    }

    /**
     * LLM-based lint analysis: detect contradictions, stale content, missing cross-references.
     */
    private List<Map<String, Object>> runLlmLintAnalysis(Map<String, String> pageContents) {
        if (pageContents.size() < 2) return List.of(); // need at least 2 pages

        // Build page summaries (first 500 chars each, cap 30000 total)
        StringBuilder context = new StringBuilder();
        int totalChars = 0;
        for (Map.Entry<String, String> entry : pageContents.entrySet()) {
            if (totalChars >= 30_000) break;
            String title = entry.getKey();
            String content = entry.getValue();
            String excerpt = content.substring(0, Math.min(content.length(), 500));
            context.append("### ").append(title).append("\n").append(excerpt).append("\n\n");
            totalChars += excerpt.length() + title.length() + 10;
        }

        String prompt = """
                你是一个知识库Wiki质量审查专家。请分析以下Wiki页面，找出以下三类问题：

                1. **矛盾 (contradictions)**：不同页面之间存在信息冲突或矛盾
                2. **过时 (stale)**：内容可能已过时，如引用旧版本号、过期日期等
                3. **缺失链接 (missing_links)**：两个页面内容相关但没有互相引用

                Wiki页面内容：
                ---
                %s
                ---

                请输出JSON格式（不要markdown包裹）：
                {
                  "contradictions": [
                    {"page": "页面标题", "related_pages": ["关联页面"], "description": "矛盾描述"}
                  ],
                  "stale": [
                    {"page": "页面标题", "description": "过时原因"}
                  ],
                  "missing_links": [
                    {"page": "页面标题", "related_pages": ["应链接的页面"], "description": "缺失链接说明"}
                  ]
                }

                如果某类问题不存在，返回空数组。所有描述用中文。
                """.formatted(context);

        String llmResponse = callDeepSeek(prompt);
        if (llmResponse == null || llmResponse.isBlank()) return List.of();

        List<Map<String, Object>> issues = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(llmResponse);

            JsonNode contradictions = root.path("contradictions");
            if (contradictions.isArray()) {
                for (JsonNode item : contradictions) {
                    List<String> related = new ArrayList<>();
                    item.path("related_pages").forEach(n -> related.add(n.asText()));
                    issues.add(lintIssue("contradiction", "warning",
                            item.path("page").asText(""),
                            item.path("description").asText(""),
                            related));
                }
            }

            JsonNode stale = root.path("stale");
            if (stale.isArray()) {
                for (JsonNode item : stale) {
                    issues.add(lintIssue("stale", "info",
                            item.path("page").asText(""),
                            item.path("description").asText(""),
                            List.of()));
                }
            }

            JsonNode missingLinks = root.path("missing_links");
            if (missingLinks.isArray()) {
                for (JsonNode item : missingLinks) {
                    List<String> related = new ArrayList<>();
                    item.path("related_pages").forEach(n -> related.add(n.asText()));
                    issues.add(lintIssue("missing_link", "info",
                            item.path("page").asText(""),
                            item.path("description").asText(""),
                            related));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse LLM lint response: {}", e.getMessage());
        }
        return issues;
    }

    /**
     * Apply wiki page create/update actions parsed from a lint-fix LLM response.
     * Writes each page through {@link #writeWikiDocument} and enqueues a DOCUMENT_PARSE
     * so the updated content is re-chunked and re-embedded. Returns {@code [created, updated]}.
     *
     * <p>Intentionally minimal compared to the deleted {@code applyWikiChanges}: it does
     * not maintain index.md or auto-trigger curation — lint fixes are targeted edits and
     * index maintenance now belongs to the wiki agent.
     */
    private int[] applyLintFixPages(String tenantId, String kbId, JsonNode response) {
        JsonNode wikiPages = response.path("wiki_pages");
        if (!wikiPages.isArray() || wikiPages.isEmpty()) {
            return new int[]{0, 0};
        }
        int created = 0, updated = 0;
        for (JsonNode page : wikiPages) {
            String title = page.path("title").asText("");
            String action = page.path("action").asText("create");
            String content = page.path("content").asText("");
            if (title.isBlank() || content.isBlank()) continue;

            String filename = titleToFilename(title);
            writeWikiDocument(tenantId, kbId, filename, title, content);
            triggerDocumentParse(tenantId, kbId, filename);
            if ("update".equals(action)) {
                updated++;
            } else {
                created++;
            }
        }
        log.info("Lint fix applied to KB {}: {} created, {} updated", kbId, created, updated);
        return new int[]{created, updated};
    }

    /**
     * Fix lint issues by calling the curate LLM with lint context appended.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fixLintIssues(String tenantId, String kbId,
                                              List<String> categories,
                                              List<Map<String, Object>> issues) {
        KnowledgeBaseEntity kb = kbAccessService.getKbWithAccess(kbId, tenantId);
        tenantId = kb.getTenantId();
        long startMs = System.currentTimeMillis();

        // Filter issues by categories (empty = all)
        List<Map<String, Object>> filtered = issues;
        if (categories != null && !categories.isEmpty()) {
            Set<String> catSet = new HashSet<>(categories);
            filtered = issues.stream()
                    .filter(i -> catSet.contains(i.get("category")))
                    .toList();
        }

        if (filtered.isEmpty()) {
            return Map.of("fixed", 0, "pages_updated", 0, "pages_created", 0);
        }

        // Build lint context string
        StringBuilder lintContext = new StringBuilder();
        lintContext.append("\n\n--- Lint Issues to Fix ---\n");
        for (Map<String, Object> issue : filtered) {
            lintContext.append("- [").append(issue.get("category")).append("] ")
                    .append(issue.get("page")).append(": ")
                    .append(issue.get("description"));
            List<String> related = (List<String>) issue.get("related_pages");
            if (related != null && !related.isEmpty()) {
                lintContext.append(" (关联: ").append(String.join(", ", related)).append(")");
            }
            lintContext.append("\n");
        }
        lintContext.append("---\n\nPlease fix the above lint issues. ");
        lintContext.append("For language issues, translate the page content to Chinese. ");
        lintContext.append("For orphan pages, add appropriate wikilinks from related pages. ");
        lintContext.append("For broken links, either create the missing page or fix the link. ");
        lintContext.append("For contradictions, resolve the conflicting information. ");
        lintContext.append("For stale content, update or flag the outdated information. ");
        lintContext.append("For missing links, add the cross-references.\n");

        // Load current index and pages (same as runCurate)
        String indexContent = readWikiPage(tenantId, kbId, "index.md");
        if (indexContent == null) indexContent = "# Wiki Index\n\nNo pages yet.\n";
        if (indexContent.length() > MAX_INDEX_CHARS) {
            indexContent = indexContent.substring(0, MAX_INDEX_CHARS);
        }

        List<DocumentEntity> wikiDocs = documentRepository.findByTenantIdAndKbIdAndDocType(
                tenantId, kbId, DOC_TYPE_WIKI);
        StringBuilder pagesContext = new StringBuilder();
        int totalChars = 0;
        for (DocumentEntity doc : wikiDocs) {
            if ("index.md".equals(doc.getFilename()) || "log.md".equals(doc.getFilename())) continue;
            if (totalChars >= 60_000) break;
            String content = readWikiPage(tenantId, kbId, doc.getFilename());
            if (content == null || content.isBlank()) continue;
            String title = doc.getFilename().replace(".md", "");
            String excerpt = content.substring(0, Math.min(content.length(), 1_500));
            pagesContext.append("### ").append(title).append("\n").append(excerpt).append("\n\n");
            totalChars += excerpt.length();
        }

        // Build prompt: curate prompt + lint context
        String prompt = String.format(DEFAULT_CURATE_PROMPT, indexContent, pagesContext) + lintContext;

        String llmResponse;
        try {
            llmResponse = callDeepSeek(prompt);
        } catch (Exception e) {
            log.error("Lint fix LLM call failed for KB {}: {}", kbId, e.getMessage());
            writeRunLog(tenantId, kbId, "lint-fix", null, 0, 0, 0, startMs, e.getMessage());
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }

        if (llmResponse == null || llmResponse.isBlank()) {
            return Map.of("fixed", 0, "pages_updated", 0, "pages_created", 0);
        }

        // Parse and apply changes (inlined from the deleted applyWikiChanges helper)
        try {
            JsonNode response = objectMapper.readTree(llmResponse);
            int[] counts = applyLintFixPages(tenantId, kbId, response);

            JsonNode deletePages = response.path("delete_pages");
            int deleted = 0;
            if (deletePages.isArray()) {
                for (JsonNode titleNode : deletePages) {
                    String title = titleNode.asText("").trim();
                    if (title.isBlank()) continue;
                    String filename = titleToFilename(title);
                    Optional<DocumentEntity> docOpt = documentRepository.findByTypeAndFilename(
                            tenantId, kbId, DOC_TYPE_WIKI, filename);
                    docOpt.ifPresent(doc -> {
                        documentRepository.delete(doc);
                        log.info("Lint fix deleted wiki page: {}", filename);
                    });
                    deleted++;
                }
            }

            appendToLog(tenantId, kbId, "[Lint修复] 修复 " + filtered.size() + " 个问题，"
                    + "创建 " + counts[0] + " 页，更新 " + counts[1] + " 页，删除 " + deleted + " 页");
            writeRunLog(tenantId, kbId, "lint-fix", null, counts[0], counts[1], deleted, startMs, null);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("fixed", filtered.size());
            result.put("pages_updated", counts[1]);
            result.put("pages_created", counts[0]);
            return result;
        } catch (Exception e) {
            log.error("Failed to apply lint fix changes for KB {}: {}", kbId, e.getMessage(), e);
            writeRunLog(tenantId, kbId, "lint-fix", null, 0, 0, 0, startMs, e.getMessage());
            throw new RuntimeException("Failed to apply fixes: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> lintIssue(String category, String severity,
                                                   String page, String description,
                                                   List<String> relatedPages) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("category", category);
        issue.put("severity", severity);
        issue.put("page", page);
        issue.put("description", description);
        issue.put("related_pages", relatedPages != null ? relatedPages : List.of());
        return issue;
    }

    /**
     * Strip markdown syntax (headings, links, bold, italic, code blocks, etc.) to get plain text.
     */
    static String stripMarkdown(String md) {
        if (md == null) return "";
        String text = md;
        // Remove code blocks
        text = text.replaceAll("```[\\s\\S]*?```", "");
        text = text.replaceAll("`[^`]+`", "");
        // Remove headings markers
        text = text.replaceAll("(?m)^#{1,6}\\s+", "");
        // Remove wikilinks but keep text
        text = text.replaceAll("\\[\\[([^\\]]+)]]", "$1");
        // Remove markdown links
        text = text.replaceAll("\\[([^\\]]+)]\\([^)]+\\)", "$1");
        // Remove bold/italic markers
        text = text.replaceAll("[*_]{1,3}", "");
        // Remove horizontal rules
        text = text.replaceAll("(?m)^[-*_]{3,}$", "");
        // Remove list markers
        text = text.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        text = text.replaceAll("(?m)^\\s*\\d+\\.\\s+", "");
        // Collapse whitespace
        text = text.replaceAll("\\s+", "");
        return text;
    }

    /**
     * Calculate the ratio of Chinese characters in a string.
     */
    static double calcChineseRatio(String text) {
        if (text == null || text.isEmpty()) return 0.0;
        long chinese = text.codePoints()
                .filter(cp -> cp >= 0x4E00 && cp <= 0x9FFF)
                .count();
        return (double) chinese / text.length();
    }

    /**
     * Convert a wiki page title to a filename: "Database Sharding" -> "database-sharding.md"
     */
    public static String titleToFilename(String title) {
        return title.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "-")
                .replaceAll("^-+|-+$", "")
                + ".md";
    }

    /**
     * Convert a filename back to a display title: "database-sharding.md" -> "database-sharding"
     */
    /** Normalize a wiki title/link for comparison: lowercase, spaces/underscores → hyphens, strip parens. */
    static String normalizeLinkTitle(String title) {
        if (title == null) return "";
        return title.toLowerCase().replaceAll("[\\s_]+", "-").replaceAll("[（()）]", "").trim();
    }

    public static String filenameToTitle(String filename) {
        if (filename.endsWith(".md")) {
            return filename.substring(0, filename.length() - 3);
        }
        return filename;
    }
}
