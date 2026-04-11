package com.lakeon.knowledge;

import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link InternalWikiController}. Uses {@code @WebMvcTest}
 * to load only the web layer plus {@link ApiKeyFilter} so we exercise the wiki
 * agent token gating from end to end.
 */
@WebMvcTest(InternalWikiController.class)
@Import(ApiKeyFilter.class)
@DisplayName("InternalWikiController API 集成测试")
class InternalWikiControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean private WikiToolService toolService;
    @MockBean private WikiRunLogRepository runLogRepository;
    @MockBean private TenantService tenantService;
    @MockBean private TenantRepository tenantRepository;
    @MockBean private LakeonProperties lakeonProperties;

    private static final String TOKEN = "Bearer test-wiki-token";

    @BeforeEach
    void setUp() {
        var agentConfig = new LakeonProperties.WikiAgentConfig();
        agentConfig.setInternalToken("test-wiki-token");
        var wikiConfig = new LakeonProperties.WikiConfig();
        wikiConfig.setAgent(agentConfig);
        when(lakeonProperties.getWiki()).thenReturn(wikiConfig);
    }

    @Test
    @DisplayName("IT-WIKI-INT-001: list_pages — 返回 JSON 数组")
    void listPagesEndpointReturnsJsonArray() throws Exception {
        when(toolService.listPages("t1", "kb1"))
                .thenReturn(List.of(Map.of("title", "Auth", "filename", "auth.md")));

        mvc.perform(post("/api/v1/internal/wiki/tool/list_pages")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"t1\",\"kb_id\":\"kb1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Auth"));
    }

    @Test
    @DisplayName("IT-WIKI-INT-002: create_page — 调用 service 并返回结果")
    void createPageEndpointDispatchesToService() throws Exception {
        when(toolService.createPage(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("ok", true, "filename", "sharding.md"));

        mvc.perform(post("/api/v1/internal/wiki/tool/create_page")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"t1\",\"kb_id\":\"kb1\",\"title\":\"Sharding\"," +
                                "\"content\":\"body\",\"tags\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.filename").value("sharding.md"));
    }

    @Test
    @DisplayName("IT-WIKI-INT-003: update_page — 透传 5 个参数")
    void updatePageEndpointDispatchesToService() throws Exception {
        when(toolService.updatePage(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("ok", true));

        mvc.perform(post("/api/v1/internal/wiki/tool/update_page")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"t1\",\"kb_id\":\"kb1\",\"title\":\"Auth\"," +
                                "\"old_text\":\"foo\",\"new_text\":\"bar\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        verify(toolService).updatePage("t1", "kb1", "Auth", "foo", "bar");
    }

    @Test
    @DisplayName("IT-WIKI-INT-004: /runlog — 持久化 entity")
    void runlogEndpointPersistsEntity() throws Exception {
        String body = "{\"tenantId\":\"t1\",\"kbId\":\"kb1\",\"runId\":\"run_x\"," +
                "\"runType\":\"ingest\",\"triggerDoc\":\"doc1.md\"," +
                "\"pagesCreated\":2,\"pagesUpdated\":5,\"pagesDeleted\":0," +
                "\"durationMs\":12345,\"status\":\"success\"," +
                "\"toolCallsCount\":18,\"tokenCount\":4200,\"source\":\"queue\"}";

        mvc.perform(post("/api/v1/internal/wiki/runlog")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        verify(runLogRepository).save(any(WikiRunLogEntity.class));
    }

    @Test
    @DisplayName("IT-WIKI-INT-005: /callback/{taskId} — 返回 202")
    void callbackEndpointReturns202() throws Exception {
        mvc.perform(post("/api/v1/internal/wiki/callback/task_abc")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"completed\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("IT-WIKI-INT-006: 缺少 token — 返回 403")
    void missingTokenReturns403() throws Exception {
        mvc.perform(post("/api/v1/internal/wiki/tool/list_pages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"t1\",\"kb_id\":\"kb1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("IT-WIKI-INT-007: 错误的 token — 返回 403")
    void wrongTokenReturns403() throws Exception {
        mvc.perform(post("/api/v1/internal/wiki/tool/list_pages")
                        .header("Authorization", "Bearer wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenant_id\":\"t1\",\"kb_id\":\"kb1\"}"))
                .andExpect(status().isForbidden());
    }
}
