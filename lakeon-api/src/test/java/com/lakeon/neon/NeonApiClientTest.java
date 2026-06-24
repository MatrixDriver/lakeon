package com.lakeon.neon;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.lakeon.neon.dto.CreateTimelineRequest;
import com.lakeon.neon.dto.NeonTimeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest(httpPort = 9091)
@DisplayName("NeonApiClient 单元测试（WireMock）")
class NeonApiClientTest {

    private NeonApiClient neonApiClient;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        neonApiClient = new NeonApiClient(wmRuntimeInfo.getHttpBaseUrl());
    }

    @Test
    @DisplayName("createBranch 发送正确的 POST body 并返回 CreateBranchResponse")
    void createBranch_postsCorrectBody() {
        // Given — pageserver accepts the branch-creation timeline POST and returns the new timeline
        stubFor(post(urlPathEqualTo("/v1/tenant/tn1/timeline"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "timeline_id": "new-tl-2",
                                  "tenant_id": "tn1",
                                  "ancestor_timeline_id": "tl1",
                                  "ancestor_lsn": "0/A1B2C3D0",
                                  "last_record_lsn": "0/A1B2C3D4"
                                }
                                """)));

        // When
        NeonApiClient.CreateBranchRequest req = new NeonApiClient.CreateBranchRequest(
                "tl1", "0/A1B2C3D0", "new-tl-2");
        NeonApiClient.CreateBranchResponse resp = neonApiClient.createBranch("tn1", req);

        // Then — response carries new timeline id and the last_record_lsn (not ancestor_lsn)
        assertThat(resp).isNotNull();
        assertThat(resp.timelineId()).isEqualTo("new-tl-2");
        assertThat(resp.lsn()).isEqualTo("0/A1B2C3D4");

        // And — request hit POST /v1/tenant/tn1/timeline with snake_case body fields
        verify(postRequestedFor(urlEqualTo("/v1/tenant/tn1/timeline"))
                .withRequestBody(matchingJsonPath("$.ancestor_timeline_id", equalTo("tl1")))
                .withRequestBody(matchingJsonPath("$.ancestor_start_lsn", equalTo("0/A1B2C3D0")))
                .withRequestBody(matchingJsonPath("$.new_timeline_id", equalTo("new-tl-2"))));
    }

    @Test
    @DisplayName("createTimeline 遇到 tenant 404 时 reattach 后重试")
    void createTimeline_reattachesTenantOnNotFound() {
        // Given - pageserver has lost the tenant attachment before timeline creation
        stubFor(post(urlPathEqualTo("/v1/tenant/tn1/timeline"))
                .inScenario("reattach")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(404))
                .willSetStateTo("reattached"));
        stubFor(put(urlPathEqualTo("/v1/tenant/tn1/location_config"))
                .willReturn(aResponse().withStatus(200)));
        stubFor(post(urlPathEqualTo("/v1/tenant/tn1/timeline"))
                .inScenario("reattach")
                .whenScenarioStateIs("reattached")
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "timeline_id": "tl1",
                                  "tenant_id": "tn1",
                                  "last_record_lsn": "0/10"
                                }
                                """)));

        // When
        NeonTimeline timeline = neonApiClient.createTimeline("tn1",
                CreateTimelineRequest.forNewTimeline("tl1", 17));

        // Then
        assertThat(timeline.getTimelineId()).isEqualTo("tl1");
        verify(putRequestedFor(urlEqualTo("/v1/tenant/tn1/location_config"))
                .withRequestBody(matchingJsonPath("$.mode", equalTo("AttachedSingle"))));
        verify(2, postRequestedFor(urlEqualTo("/v1/tenant/tn1/timeline")));
    }

    @Test
    @DisplayName("getTimelineInfo 调用正确的 endpoint 并返回 TimelineInfo")
    void getTimelineInfo_callsCorrectEndpoint() {
        // Given — pageserver returns timeline metadata for GET /v1/tenant/{tn}/timeline/{tl}
        stubFor(get(urlPathEqualTo("/v1/tenant/tn1/timeline/tl1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "timeline_id": "tl1",
                                  "last_record_lsn": "0/FF",
                                  "disk_consistent_lsn": "0/FE",
                                  "latest_gc_cutoff_lsn": "0/AA"
                                }
                                """)));

        // When
        NeonApiClient.TimelineInfo info = neonApiClient.getTimelineInfo("tn1", "tl1");

        // Then
        assertThat(info).isNotNull();
        assertThat(info.timelineId()).isEqualTo("tl1");
        assertThat(info.lastRecordLsn()).isEqualTo("0/FF");
        assertThat(info.diskConsistentLsn()).isEqualTo("0/FE");
        assertThat(info.latestGcCutoffLsn()).isEqualTo("0/AA");
        verify(getRequestedFor(urlEqualTo("/v1/tenant/tn1/timeline/tl1")));
    }

    @Test
    @DisplayName("getLsnByTimestamp 调用正确的 endpoint 并返回 LSN")
    void getLsnByTimestamp_callsCorrectEndpoint() {
        // Given — Pageserver returns the LSN for a given RFC3339 timestamp
        stubFor(get(urlPathEqualTo("/v1/tenant/tn1/timeline/tl1/get_lsn_by_timestamp"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"lsn": "0/A1B2C3D4"}
                                """)));

        // When
        String lsn = neonApiClient.getLsnByTimestamp(
                "tn1", "tl1", Instant.parse("2026-05-21T14:30:00Z"));

        // Then
        assertThat(lsn).isEqualTo("0/A1B2C3D4");
        verify(getRequestedFor(urlEqualTo(
                "/v1/tenant/tn1/timeline/tl1/get_lsn_by_timestamp?timestamp=2026-05-21T14%3A30%3A00Z")));
    }
}
