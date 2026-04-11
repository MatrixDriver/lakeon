package com.lakeon.knowledge;

import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.TenantService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the user-facing wiki agent dispatch endpoints on
 * {@link KnowledgeController}. Uses {@code @WebMvcTest} with {@link ApiKeyFilter}
 * so the normal user API key auth path is exercised end to end.
 */
@WebMvcTest(KnowledgeController.class)
@Import(ApiKeyFilter.class)
@DisplayName("KnowledgeController wiki agent 用户面 API 集成测试")
class KnowledgeControllerWikiAgentTest {

    @Autowired private MockMvc mvc;

    // Controller dependencies
    @MockBean private KnowledgeService knowledgeService;
    @MockBean private DocumentRepository documentRepository;
    @MockBean private KbWriteQueue kbWriteQueue;
    @MockBean private KnowledgeBaseRepository knowledgeBaseRepository;
    @MockBean private WikiService wikiService;
    @MockBean private WikiRunLogRepository wikiRunLogRepository;
    @MockBean private LakeonProperties lakeonProperties;
    @MockBean private KbAccessService kbAccessService;
    @MockBean private KbShareRepository kbShareRepository;
    @MockBean private TenantRepository tenantRepository;
    @MockBean private ChunkService chunkService;
    @MockBean private com.lakeon.repository.DatabaseRepository databaseRepository;
    @MockBean private WikiAgentClient wikiAgentClient;
    @MockBean private WikiToolService wikiToolService;

    // Filter deps
    @MockBean private TenantService tenantService;

    private static final String AUTH = "Bearer user-key";

    @BeforeEach
    void setupAuth() {
        // Ensure LakeonProperties.proxy is non-null so ApiKeyFilter proxy branch works
        // (ApiKeyFilter only reads props.getProxy() for /proxy/**, but it also reads
        // props.getWiki()/props.getAdmin() for gated paths — our test paths skip those).

        // User API key → tenant lookup
        TenantEntity tenant = new TenantEntity();
        tenant.setId("tenant-1");
        tenant.setName("test");
        tenant.setDisabled(false);
        when(tenantService.authenticateByApiKey("user-key")).thenReturn(tenant);

        // Mock KB access: both owner and shared paths go through getKbWithAccess
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setId("kb-1");
        kb.setTenantId("tenant-1");
        when(kbAccessService.getKbWithAccess("kb-1", "tenant-1")).thenReturn(kb);
    }

    @Test
    @DisplayName("triggerWikiAgentIngest — 提交 task_id 返回 accepted")
    void triggerWikiAgentIngest_accepted() throws Exception {
        when(wikiAgentClient.triggerIngest("tenant-1", "kb-1", "doc-1"))
                .thenReturn("task_x");

        mvc.perform(post("/api/v1/knowledge/bases/kb-1/wiki/agent/ingest")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"document_id\":\"doc-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_id").value("task_x"))
                .andExpect(jsonPath("$.run_id").value("task_x"))
                .andExpect(jsonPath("$.status").value("accepted"));
    }

    @Test
    @DisplayName("triggerWikiAgentIngest — 缺少 document_id 返回 400")
    void triggerWikiAgentIngest_missingDocId_returnsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/knowledge/bases/kb-1/wiki/agent/ingest")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("triggerWikiAgentIngest — agent 不可用返回 503")
    void triggerWikiAgentIngest_unavailable_returns503() throws Exception {
        when(wikiAgentClient.triggerIngest(any(), any(), any())).thenReturn(null);

        mvc.perform(post("/api/v1/knowledge/bases/kb-1/wiki/agent/ingest")
                        .header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"document_id\":\"doc-1\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("getWikiAgentTaskStatus — 透传 WikiAgentClient 响应")
    void getWikiAgentTaskStatus_forwardsToClient() throws Exception {
        when(wikiAgentClient.getTaskStatus("task_x"))
                .thenReturn(Map.of(
                        "task_id", "task_x",
                        "status", "completed",
                        "result", Map.of("pages_created", 2)));

        mvc.perform(get("/api/v1/knowledge/bases/kb-1/wiki/agent/tasks/task_x")
                        .header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_id").value("task_x"))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.result.pages_created").value(2));
    }

    @Test
    @DisplayName("searchWikiPages — 代理到 WikiToolService")
    void searchWikiPages_delegatesToToolService() throws Exception {
        when(wikiToolService.searchPages("tenant-1", "kb-1", "auth", 5))
                .thenReturn(List.of(Map.of("title", "Auth", "score", 3)));

        mvc.perform(get("/api/v1/knowledge/bases/kb-1/wiki/search")
                        .header("Authorization", AUTH)
                        .param("query", "auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Auth"))
                .andExpect(jsonPath("$[0].score").value(3));
    }

    @Test
    @DisplayName("readWikiPageByTitle — 命中返回 content")
    void readWikiPageByTitle_found() throws Exception {
        when(wikiService.readWikiPage("tenant-1", "kb-1", "auth.md"))
                .thenReturn("# Auth\n\nbody");

        mvc.perform(get("/api/v1/knowledge/bases/kb-1/wiki/page-by-title")
                        .header("Authorization", AUTH)
                        .param("title", "Auth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.title").value("Auth"))
                .andExpect(jsonPath("$.filename").value("auth.md"))
                .andExpect(jsonPath("$.content").value("# Auth\n\nbody"));
    }

    @Test
    @DisplayName("readWikiPageByTitle — 未命中返回 found=false")
    void readWikiPageByTitle_notFound() throws Exception {
        when(wikiService.readWikiPage("tenant-1", "kb-1", "missing.md"))
                .thenReturn(null);

        mvc.perform(get("/api/v1/knowledge/bases/kb-1/wiki/page-by-title")
                        .header("Authorization", AUTH)
                        .param("title", "Missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.title").value("Missing"));
    }
}
