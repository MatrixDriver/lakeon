package com.lakeon.config;

import com.lakeon.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("ApiKeyFilter internal wiki token tests")
class ApiKeyFilterInternalWikiTest {

    private LakeonProperties props;
    private ApiKeyFilter filter;

    @BeforeEach
    void setup() {
        props = new LakeonProperties();
        props.getWiki().getAgent().setInternalToken("test-token");
        TenantService tenantService = mock(TenantService.class);
        filter = new ApiKeyFilter(tenantService, props);
    }

    @Test
    @DisplayName("valid token passes through to chain")
    void validTokenPassesThrough() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/internal/wiki/tool/list_pages");
        request.addHeader("Authorization", "Bearer test-token");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // MockFilterChain records the request it was invoked with
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("invalid token is rejected with 403 and chain not invoked")
    void invalidTokenIsRejected() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/internal/wiki/tool/list_pages");
        request.addHeader("Authorization", "Bearer wrong");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Invalid wiki agent token");
        // chain was never invoked — its request stays null
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("missing config returns 503")
    void missingConfigReturns503() throws Exception {
        props.getWiki().getAgent().setInternalToken(null);

        var request = new MockHttpServletRequest("POST", "/api/v1/internal/wiki/tool/list_pages");
        request.addHeader("Authorization", "Bearer test-token");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("Wiki agent integration not configured");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("blank config returns 503")
    void blankConfigReturns503() throws Exception {
        props.getWiki().getAgent().setInternalToken("   ");

        var request = new MockHttpServletRequest("POST", "/api/v1/internal/wiki/tool/list_pages");
        request.addHeader("Authorization", "Bearer test-token");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }
}
