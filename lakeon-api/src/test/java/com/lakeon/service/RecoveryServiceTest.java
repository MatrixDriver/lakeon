package com.lakeon.service;

import com.lakeon.model.dto.PitrRequest;
import com.lakeon.model.dto.PitrResponse;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.repository.DatabaseRepository;
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

    @Test
    void pitr_createsNewBranchAndDatabase() {
        DatabaseEntity src = new DatabaseEntity();
        src.setId("db_old");
        src.setName("mydb");
        src.setTenantId("tn1");
        src.setNeonTenantId("nt1");
        src.setNeonTimelineId("tl_old");
        when(databaseRepository.findById("db_old")).thenReturn(Optional.of(src));
        when(neonApiClient.getLsnByTimestamp(eq("nt1"), eq("tl_old"), any()))
            .thenReturn("0/AB12");
        when(neonApiClient.createBranch(eq("nt1"), any()))
            .thenReturn(new NeonApiClient.CreateBranchResponse("tl_new", "0/AB12"));
        DatabaseEntity newDb = new DatabaseEntity();
        newDb.setId("db_new");
        newDb.setName("mydb_restored_20260521");
        when(databaseService.registerRecoveredDatabase(eq("tn1"), eq("tl_new"), eq("mydb_restored_20260521")))
            .thenReturn(newDb);

        RecoveryService svc = new RecoveryService(databaseRepository, neonApiClient, databaseService);
        PitrResponse resp = svc.pitr("db_old",
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
        when(databaseRepository.findById("nope")).thenReturn(Optional.empty());
        RecoveryService svc = new RecoveryService(databaseRepository, neonApiClient, databaseService);
        assertThatThrownBy(() -> svc.pitr("nope",
                new PitrRequest(Instant.now(), null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("database not found");
    }
}
