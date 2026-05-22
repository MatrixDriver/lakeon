package com.lakeon.service;

import com.lakeon.model.dto.PitrRequest;
import com.lakeon.model.dto.PitrResponse;
import com.lakeon.model.dto.PitrWindow;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoveryServiceTest {

    @Mock DatabaseRepository databaseRepository;
    @Mock NeonApiClient neonApiClient;
    @Mock DatabaseService databaseService;

    private static TenantEntity tenant(String id) {
        TenantEntity t = new TenantEntity();
        t.setId(id);
        return t;
    }

    @Test
    void pitr_createsNewBranchAndDatabase() {
        TenantEntity tenant = tenant("tn1");
        DatabaseEntity src = new DatabaseEntity();
        src.setId("db_old");
        src.setName("mydb");
        src.setTenantId("tn1");
        src.setNeonTenantId("nt1");
        src.setNeonTimelineId("tl_old");
        src.setDbUser("user_old");
        src.setDbPassword("pw_old");
        src.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(databaseRepository.findByIdAndTenantId("db_old", "tn1")).thenReturn(Optional.of(src));
        when(neonApiClient.getLsnByTimestamp(eq("nt1"), eq("tl_old"), any()))
            .thenReturn("0/AB12");
        when(neonApiClient.createBranch(eq("nt1"), any()))
            .thenReturn(new NeonApiClient.CreateBranchResponse("tl_new", "0/AB12"));
        DatabaseEntity newDb = new DatabaseEntity();
        newDb.setId("db_new");
        newDb.setName("mydb_restored_20260521");
        when(databaseService.registerRecoveredDatabase(eq("tn1"), eq("nt1"), eq("tl_new"),
                eq("mydb_restored_20260521"), eq("user_old"), eq("pw_old")))
            .thenReturn(newDb);

        RecoveryService svc = new RecoveryService(databaseRepository, neonApiClient, databaseService);
        PitrResponse resp = svc.pitr(tenant, "db_old",
            new PitrRequest(Instant.parse("2026-05-21T14:30:00Z"), "mydb_restored_20260521"));

        assertThat(resp.newDbId()).isEqualTo("db_new");
        assertThat(resp.lsn()).isEqualTo("0/AB12");
        assertThat(resp.status()).isEqualTo("ready");
        verify(neonApiClient).createBranch(eq("nt1"),
            argThat(req -> req.ancestorTimelineId().equals("tl_old")
                        && req.ancestorStartLsn().equals("0/AB12")));
    }

    @Test
    void pitr_throwsWhenDatabaseNotFound() {
        TenantEntity tenant = tenant("tn1");
        when(databaseRepository.findByIdAndTenantId("nope", "tn1")).thenReturn(Optional.empty());
        RecoveryService svc = new RecoveryService(databaseRepository, neonApiClient, databaseService);
        assertThatThrownBy(() -> svc.pitr(tenant, "nope",
                new PitrRequest(Instant.now(), null)))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Database not found");
    }

    @Test
    void pitr_throwsNotFoundWhenDbBelongsToAnotherTenant() {
        TenantEntity caller = tenant("tn2");
        when(databaseRepository.findByIdAndTenantId("db_old", "tn2")).thenReturn(Optional.empty());

        RecoveryService svc = new RecoveryService(databaseRepository, neonApiClient, databaseService);
        assertThatThrownBy(() -> svc.pitr(caller, "db_old",
                new PitrRequest(Instant.parse("2026-05-21T14:30:00Z"), null)))
            .isInstanceOf(NotFoundException.class)
            .hasMessage("Database not found: db_old");
    }

    @Test
    void getPitrWindow_returnsCreatedAtAndLatestLsn() {
        TenantEntity tenant = tenant("tn1");
        DatabaseEntity db = new DatabaseEntity();
        db.setId("db1");
        db.setTenantId("tn1");
        db.setNeonTenantId("nt1");
        db.setNeonTimelineId("tl1");
        db.setCreatedAt(Instant.parse("2026-04-01T00:00:00Z"));
        when(databaseRepository.findByIdAndTenantId("db1", "tn1")).thenReturn(Optional.of(db));
        when(neonApiClient.getTimelineInfo("nt1", "tl1"))
            .thenReturn(new NeonApiClient.TimelineInfo("tl1", "0/FFFF", "0/FFFE", "0/AAAA"));

        RecoveryService svc = new RecoveryService(databaseRepository, neonApiClient, databaseService);
        PitrWindow window = svc.getPitrWindow(tenant, "db1");

        assertThat(window.earliest()).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
        assertThat(window.latestLsn()).isEqualTo("0/FFFF");
        // earliestLsn is the timeline's latest_gc_cutoff_lsn (earliest queryable LSN)
        assertThat(window.earliestLsn()).isEqualTo("0/AAAA");
    }

    @Test
    void getPitrWindow_throwsNotFoundWhenDatabaseMissing() {
        TenantEntity tenant = tenant("tn1");
        when(databaseRepository.findByIdAndTenantId("nope", "tn1")).thenReturn(Optional.empty());
        RecoveryService svc = new RecoveryService(databaseRepository, neonApiClient, databaseService);
        assertThatThrownBy(() -> svc.getPitrWindow(tenant, "nope"))
            .isInstanceOf(NotFoundException.class);
    }
}
