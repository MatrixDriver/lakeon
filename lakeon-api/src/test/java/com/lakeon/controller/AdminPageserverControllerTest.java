package com.lakeon.controller;

import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.pageserver.PageserverNode;
import com.lakeon.pageserver.PageserverNodeStatus;
import com.lakeon.pageserver.PageserverPlacement;
import com.lakeon.pageserver.PageserverPlacementService;
import com.lakeon.pageserver.PageserverRebalancePlan;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.InviteCodeRepository;
import com.lakeon.repository.OperationLogRepository;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.AdminService;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
@Import(ApiKeyFilter.class)
class AdminPageserverControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantService tenantService;
    @MockBean
    private TenantRepository tenantRepository;
    @MockBean
    private DatabaseRepository databaseRepository;
    @MockBean
    private DatabaseService databaseService;
    @MockBean
    private AdminService adminService;
    @MockBean
    private InviteCodeRepository inviteCodeRepository;
    @MockBean
    private OperationLogRepository operationLogRepository;
    @MockBean
    private PageserverPlacementService pageserverPlacementService;
    @MockBean
    private OperationLogRepository operationLogRepository;
    @MockBean
    private LakeonProperties lakeonProperties;

    private final LakeonProperties.AdminConfig adminConfig = new LakeonProperties.AdminConfig();
    private final LakeonProperties.ProxyConfig proxyConfig = new LakeonProperties.ProxyConfig();

    @BeforeEach
    void setUp() {
        adminConfig.setToken("test-admin-token");
        when(lakeonProperties.getAdmin()).thenReturn(adminConfig);
        when(lakeonProperties.getProxy()).thenReturn(proxyConfig);
    }

    @Test
    void topologyReturnsNodesAndCurrentPlacements() throws Exception {
        PageserverNode node = new PageserverNode("ps-0", "http://pageserver-0:9898", "pageserver-0", 6400);
        when(pageserverPlacementService.nodeStatuses()).thenReturn(List.of(
            new PageserverNodeStatus(node, true, 0.12d, "dicer")
        ));
        when(pageserverPlacementService.placements()).thenReturn(List.of(
            new PageserverPlacement("tenant-a", 0, node, 2L, "dicer-load-aware")
        ));

        mockMvc.perform(get("/api/v1/admin/pageserver/topology")
                .header("Authorization", "Bearer test-admin-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nodes", hasSize(1)))
            .andExpect(jsonPath("$.nodes[0].id").value("ps-0"))
            .andExpect(jsonPath("$.nodes[0].healthy").value(true))
            .andExpect(jsonPath("$.placements[0].tenant_id").value("tenant-a"))
            .andExpect(jsonPath("$.placements[0].epoch").value(2));
    }

    @Test
    void databaseDetailReturnsDirectAndPooledConnectionUris() throws Exception {
        DatabaseEntity db = new DatabaseEntity();
        db.setId("db_pool");
        db.setTenantId("tn_pool");
        db.setName("app-db");
        db.setStatus(DatabaseStatus.RUNNING);
        db.setConnectionUri("postgres://user@pg.dbay.cloud:4432/app-db?options=endpoint%3Dapp-db");
        db.setStorageLimitGb(10);

        when(databaseRepository.findById("db_pool")).thenReturn(Optional.of(db));
        when(databaseService.buildPooledConnectionUri(db.getConnectionUri()))
            .thenReturn("postgres://user@pg.dbay.cloud:4432/app-db?options=endpoint%3Dapp-db-pooler");

        mockMvc.perform(get("/api/v1/admin/databases/db_pool")
                .header("Authorization", "Bearer test-admin-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connection_uri").value("postgres://user@pg.dbay.cloud:4432/app-db?options=endpoint%3Dapp-db"))
            .andExpect(jsonPath("$.pooled_connection_uri").value("postgres://user@pg.dbay.cloud:4432/app-db?options=endpoint%3Dapp-db-pooler"));
    }

    @Test
    void failoverEndpointReturnsPlannedMoves() throws Exception {
        when(pageserverPlacementService.failoverNode("ps-0")).thenReturn(new PageserverRebalancePlan(false, List.of(
            new PageserverRebalancePlan.Move("tenant-a", 0, "ps-0", "ps-1", 4L, "node-unavailable:ps-0")
        )));

        mockMvc.perform(post("/api/v1/admin/pageserver/nodes/ps-0/failover")
                .header("Authorization", "Bearer test-admin-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dry_run").value(false))
            .andExpect(jsonPath("$.moves", hasSize(1)))
            .andExpect(jsonPath("$.moves[0].from_node_id").value("ps-0"))
            .andExpect(jsonPath("$.moves[0].to_node_id").value("ps-1"))
            .andExpect(jsonPath("$.moves[0].next_epoch").value(4));
    }
}
