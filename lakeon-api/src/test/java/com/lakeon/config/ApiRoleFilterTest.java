package com.lakeon.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiRoleFilterTest {
    @Test
    void allRoleAllowsEveryRoute() throws Exception {
        ApiRoleFilter filter = filterWithRole("all");
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/admin/dashboard"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void adminRoleAllowsOnlyAdminAndSharedRoutes() throws Exception {
        ApiRoleFilter filter = filterWithRole("admin");

        var adminChain = new MockFilterChain();
        var adminResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/admin/dashboard"), adminResponse, adminChain);
        assertThat(adminChain.getRequest()).isNotNull();

        var actuatorChain = new MockFilterChain();
        var actuatorResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), actuatorResponse, actuatorChain);
        assertThat(actuatorChain.getRequest()).isNotNull();

        var businessChain = new MockFilterChain();
        var businessResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/databases"), businessResponse, businessChain);
        assertThat(businessChain.getRequest()).isNull();
        assertThat(businessResponse.getStatus()).isEqualTo(404);

        var proxyChain = new MockFilterChain();
        var proxyResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/proxy/wake_compute"), proxyResponse, proxyChain);
        assertThat(proxyChain.getRequest()).isNull();
        assertThat(proxyResponse.getStatus()).isEqualTo(404);
    }

    @Test
    void servingRoleRejectsAdminAndAllowsBusinessAndProxyRoutes() throws Exception {
        ApiRoleFilter filter = filterWithRole("serving");

        var businessChain = new MockFilterChain();
        var businessResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/databases"), businessResponse, businessChain);
        assertThat(businessChain.getRequest()).isNotNull();

        var proxyChain = new MockFilterChain();
        var proxyResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/proxy/wake_compute"), proxyResponse, proxyChain);
        assertThat(proxyChain.getRequest()).isNotNull();

        var adminChain = new MockFilterChain();
        var adminResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/admin/dashboard"), adminResponse, adminChain);
        assertThat(adminChain.getRequest()).isNull();
        assertThat(adminResponse.getStatus()).isEqualTo(404);
    }

    @Test
    void invalidRoleFailsClosed() throws Exception {
        ApiRoleFilter filter = filterWithRole("unknown");
        var chain = new MockFilterChain();
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/databases"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(503);
    }

    private ApiRoleFilter filterWithRole(String role) {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("LAKEON_API_ROLE")).thenReturn(role);
        return new ApiRoleFilter(environment);
    }
}
