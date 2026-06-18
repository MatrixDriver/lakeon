package com.lakeon.neon;

import com.lakeon.neon.dto.CreateTenantRequest;
import com.lakeon.neon.dto.CreateTimelineRequest;
import com.lakeon.neon.exception.NeonApiException;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
@DisplayName("NeonApiClient 集成测试（WireMock）")
class NeonApiClientIntegrationTest {

    private NeonApiClient neonApiClient;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        neonApiClient = new NeonApiClient(wmRuntimeInfo.getHttpBaseUrl());
    }

    @Nested
    @DisplayName("创建 Tenant")
    class CreateTenant {

        @Test
        @DisplayName("IT-NEON-001: 创建 tenant — 正常，返回 tenant_id")
        void createTenant_success() {
            // Given
            stubFor(post(urlEqualTo("/v1/tenant"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "id": "tenant-abc-123"
                                    }
                                    """)));

            // When
            var result = neonApiClient.createTenant(new CreateTenantRequest());

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo("tenant-abc-123");
            verify(postRequestedFor(urlEqualTo("/v1/tenant")));
        }

        @Test
        @DisplayName("IT-NEON-002: 创建 tenant — Pageserver 返回 500，抛出异常")
        void createTenant_serverError_throws() {
            // Given
            stubFor(post(urlEqualTo("/v1/tenant"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withBody("Internal Server Error")));

            // When / Then
            assertThatThrownBy(() ->
                    neonApiClient.createTenant(new CreateTenantRequest()))
                    .isInstanceOf(NeonApiException.class)
                    .hasMessageContaining("500");
        }
    }

    @Nested
    @DisplayName("创建 Timeline")
    class CreateTimeline {

        @Test
        @DisplayName("IT-NEON-003: 创建 timeline — 正常，返回 timeline_id")
        void createTimeline_success() {
            // Given
            stubFor(post(urlEqualTo("/v1/tenant/tenant-abc/timeline"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {
                                      "timeline_id": "timeline-main-001"
                                    }
                                    """)));

            // When
            var result = neonApiClient.createTimeline("tenant-abc", CreateTimelineRequest.forNewTimeline("test-timeline", 17));

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTimelineId()).isEqualTo("timeline-main-001");
        }

        @Test
        @DisplayName("IT-NEON-004: 创建 timeline — 超时处理")
        void createTimeline_timeout_throws() {
            // Given
            stubFor(post(urlEqualTo("/v1/tenant/tenant-abc/timeline"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withFixedDelay(30000))); // 30 秒延迟，触发超时

            // When / Then
            assertThatThrownBy(() ->
                    neonApiClient.createTimeline("tenant-abc", CreateTimelineRequest.forNewTimeline("test-timeline", 17)))
                    .isInstanceOf(NeonApiException.class);
        }
    }

    @Nested
    @DisplayName("删除 Tenant")
    class DeleteTenant {

        @Test
        @DisplayName("IT-NEON-005: 删除 tenant — 正常")
        void deleteTenant_success() {
            // Given
            stubFor(delete(urlEqualTo("/v1/tenant/tenant-del"))
                    .willReturn(aResponse().withStatus(200)));

            // When
            neonApiClient.deleteTenant("tenant-del");

            // Then
            verify(deleteRequestedFor(urlEqualTo("/v1/tenant/tenant-del")));
        }

        @Test
        @DisplayName("IT-NEON-006: 删除 tenant — Pageserver 返回 404，幂等处理不抛异常")
        void deleteTenant_notFound_idempotent() {
            // Given
            stubFor(delete(urlEqualTo("/v1/tenant/tenant-gone"))
                    .willReturn(aResponse().withStatus(404)));

            // When — 不应抛出异常
            neonApiClient.deleteTenant("tenant-gone");

            // Then
            verify(deleteRequestedFor(urlEqualTo("/v1/tenant/tenant-gone")));
        }
    }

    @Nested
    @DisplayName("列出 Timelines")
    class ListTimelines {

        @Test
        @DisplayName("IT-NEON-007: 列出 timelines — 正常返回列表")
        void listTimelines_success() {
            // Given
            stubFor(get(urlEqualTo("/v1/tenant/tenant-list/timeline"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    [
                                      {"timeline_id": "tl-001"},
                                      {"timeline_id": "tl-002"}
                                    ]
                                    """)));

            // When
            var result = neonApiClient.listTimelines("tenant-list");

            // Then
            assertThat(result).hasSize(2);
            assertThat(result).extracting("timelineId")
                    .containsExactly("tl-001", "tl-002");
        }
    }

    @Nested
    @DisplayName("连接故障")
    class ConnectionFailure {

        @Test
        @DisplayName("IT-NEON-008: Pageserver 连接失败 — 抛出连接异常")
        void connectionFailure_throws() {
            // Given — 使用一个不存在的端口
            var clientWithBadUrl = new NeonApiClient("http://localhost:19999");

            // When / Then
            assertThatThrownBy(() ->
                    clientWithBadUrl.createTenant(new CreateTenantRequest()))
                    .isInstanceOf(NeonApiException.class);
        }
    }
}
