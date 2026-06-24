package com.lakeon.controller;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.ComputeStatus;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.DatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProxyAdapterControllerTest {

    @Mock
    DatabaseService databaseService;

    @Mock
    DatabaseRepository databaseRepository;

    @Mock
    BranchRepository branchRepository;

    @Mock
    ComputePodManager computePodManager;

    @Test
    void wakeCompute_stripsPoolerSuffixAndMarksPooledMode() {
        DatabaseEntity db = new DatabaseEntity();
        db.setId("db_123");
        db.setName("mydb");
        db.setStatus(DatabaseStatus.RUNNING);

        BranchEntity branch = new BranchEntity();
        branch.setId("br_main");
        branch.setName("main");
        branch.setDatabaseId("db_123");
        branch.setIsDefault(true);
        branch.setComputeStatus(ComputeStatus.RUNNING);
        branch.setComputePodName("compute-br-main");
        branch.setComputeHost("10.0.0.8");
        branch.setComputePort(55433);

        when(databaseRepository.findByName("mydb")).thenReturn(Optional.of(db));
        when(branchRepository.findByDatabaseIdAndIsDefaultTrue("db_123")).thenReturn(Optional.of(branch));
        when(computePodManager.isPodReady("compute-br-main")).thenReturn(true);

        ProxyAdapterController controller = new ProxyAdapterController(
                databaseService, databaseRepository, branchRepository, computePodManager);

        Map<String, Object> result = controller.wakeCompute("mydb-pooler", "session-1", null);

        assertThat(result.get("address")).isEqualTo("10.0.0.8:55433");
        Map<?, ?> aux = (Map<?, ?>) result.get("aux");
        assertThat(aux.get("pooler_mode")).isEqualTo("PROXY_POOLED");
        verify(databaseRepository).findByName("mydb");
    }
}
