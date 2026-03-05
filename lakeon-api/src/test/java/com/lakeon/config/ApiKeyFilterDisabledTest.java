package com.lakeon.config;

import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.TenantService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyFilter 禁用租户测试")
class ApiKeyFilterDisabledTest {

    @Mock
    private TenantService tenantService;

    @Mock
    private LakeonProperties props;

    @InjectMocks
    private ApiKeyFilter apiKeyFilter;

    @Test
    @DisplayName("UT-FILTER-001: 已禁用租户 — 返回 403")
    void disabledTenant_returns403() throws Exception {
        // Given
        var tenant = new TenantEntity();
        tenant.setId("tn_dis001");
        tenant.setName("disabled-tenant");
        tenant.setApiKey("lk_valid_key");
        tenant.setDisabled(true);

        when(tenantService.authenticateByApiKey("lk_valid_key")).thenReturn(tenant);

        var request = new MockHttpServletRequest("GET", "/api/v1/databases");
        request.addHeader("Authorization", "Bearer lk_valid_key");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        // When
        apiKeyFilter.doFilter(request, response, chain);

        // Then
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("Tenant is disabled");
    }

    @Test
    @DisplayName("UT-FILTER-002: 正常租户 — 通过过滤器")
    void enabledTenant_passesThrough() throws Exception {
        // Given
        var tenant = new TenantEntity();
        tenant.setId("tn_en001");
        tenant.setName("active-tenant");
        tenant.setApiKey("lk_active_key");
        tenant.setDisabled(false);

        when(tenantService.authenticateByApiKey("lk_active_key")).thenReturn(tenant);

        var request = new MockHttpServletRequest("GET", "/api/v1/databases");
        request.addHeader("Authorization", "Bearer lk_active_key");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        // When
        apiKeyFilter.doFilter(request, response, chain);

        // Then
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute("tenant")).isEqualTo(tenant);
    }

    @Test
    @DisplayName("UT-FILTER-003: disabled=null 视为未禁用 — 通过过滤器")
    void nullDisabled_passesThrough() throws Exception {
        // Given
        var tenant = new TenantEntity();
        tenant.setId("tn_null01");
        tenant.setName("null-disabled-tenant");
        tenant.setApiKey("lk_null_key");
        // disabled is null (not set)

        when(tenantService.authenticateByApiKey("lk_null_key")).thenReturn(tenant);

        var request = new MockHttpServletRequest("GET", "/api/v1/databases");
        request.addHeader("Authorization", "Bearer lk_null_key");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        // When
        apiKeyFilter.doFilter(request, response, chain);

        // Then
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
