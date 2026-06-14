package com.lakeon.agentfs;

import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LBFSAutoJobController.class)
@Import(ApiKeyFilter.class)
class LBFSAutoJobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LBFSAutoJobRepository jobRepository;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private LakeonProperties lakeonProperties;

    @BeforeEach
    void setUp() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("tn_test");
        tenant.setName("test");
        tenant.setApiKey("test-api-key-valid-32chars!!!");
        when(tenantService.authenticateByApiKey("test-api-key-valid-32chars!!!"))
                .thenReturn(tenant);
    }

    @Test
    void lists_auto_jobs_for_folder() throws Exception {
        LBFSAutoJobEntity job = new LBFSAutoJobEntity();
        job.setId("job_1");
        job.setTenantId("tn_test");
        job.setFolderId("fld_1");
        job.setSourcePath("/datasets/orders.csv");
        job.setSourceEtag("etag_1");
        job.setProfile("dataset");
        job.setStatus("running");
        job.setAttempts(1);
        job.setCreatedAt(Instant.parse("2026-06-14T00:00:00Z"));
        when(jobRepository.findByTenantIdAndFolderIdOrderByCreatedAtDesc("tn_test", "fld_1"))
                .thenReturn(List.of(job));

        mockMvc.perform(get("/api/v1/lbfs/folders/fld_1/auto-jobs")
                        .header("Authorization", "Bearer test-api-key-valid-32chars!!!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auto_jobs[0].id").value("job_1"))
                .andExpect(jsonPath("$.auto_jobs[0].folder_id").value("fld_1"))
                .andExpect(jsonPath("$.auto_jobs[0].source_path").value("/datasets/orders.csv"))
                .andExpect(jsonPath("$.auto_jobs[0].profile").value("dataset"))
                .andExpect(jsonPath("$.auto_jobs[0].status").value("running"))
                .andExpect(jsonPath("$.auto_jobs[0].attempts").value(1));
    }
}
