package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.ImportTaskEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.model.enums.ImportMode;
import com.lakeon.model.enums.ImportTaskStatus;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.ImportTableTaskRepository;
import com.lakeon.repository.ImportTaskRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SyncStatusCollector 单元测试")
class SyncStatusCollectorTest {

    @Mock
    private ImportTaskRepository importTaskRepository;

    @Mock
    private ImportTableTaskRepository importTableTaskRepository;

    @Mock
    private DatabaseRepository databaseRepository;

    private LakeonProperties props;
    private SyncStatusCollector syncStatusCollector;

    @BeforeEach
    void setUp() {
        props = new LakeonProperties();
        var syncConfig = new LakeonProperties.SyncConfig();
        syncConfig.setWalWarnBytes(1073741824L);
        props.setSync(syncConfig);
        syncStatusCollector = new SyncStatusCollector(
                importTaskRepository, importTableTaskRepository, databaseRepository, props);
    }

    @Nested
    @DisplayName("collectSyncStatus")
    class CollectSyncStatusTests {

        @Test
        @DisplayName("UT-SVC-SS-001: skips non-SYNC mode tasks")
        void skipNonSyncModeTasks() {
            var task = new ImportTaskEntity();
            task.setId("task_full");
            task.setMode(ImportMode.FULL);
            task.setStatus(ImportTaskStatus.SYNCING);

            when(importTaskRepository.findAllByStatus(ImportTaskStatus.SYNCING))
                    .thenReturn(new ArrayList<>(List.of(task)));
            when(importTaskRepository.findAllByStatus(ImportTaskStatus.CATCHING_UP))
                    .thenReturn(new ArrayList<>());

            syncStatusCollector.collectSyncStatus();

            verify(databaseRepository, never()).findById(any());
        }

        @Test
        @DisplayName("UT-SVC-SS-002: processes SYNC mode tasks — database not found")
        void syncModeTask_databaseNotFound() {
            var task = new ImportTaskEntity();
            task.setId("task_sync");
            task.setMode(ImportMode.SYNC);
            task.setStatus(ImportTaskStatus.SYNCING);
            task.setDatabaseId("db_test");

            when(importTaskRepository.findAllByStatus(ImportTaskStatus.SYNCING))
                    .thenReturn(new ArrayList<>(List.of(task)));
            when(importTaskRepository.findAllByStatus(ImportTaskStatus.CATCHING_UP))
                    .thenReturn(new ArrayList<>());
            when(databaseRepository.findById("db_test"))
                    .thenReturn(Optional.empty());

            syncStatusCollector.collectSyncStatus();

            verify(databaseRepository).findById("db_test");
            verify(importTaskRepository, never()).save(any());
        }

        @Test
        @DisplayName("UT-SVC-SS-003: skips when database is SUSPENDED")
        void skipWhenDatabaseSuspended() {
            var task = new ImportTaskEntity();
            task.setId("task_suspended");
            task.setMode(ImportMode.SYNC);
            task.setStatus(ImportTaskStatus.SYNCING);
            task.setDatabaseId("db_test");

            var database = new DatabaseEntity();
            database.setId("db_test");
            database.setStatus(DatabaseStatus.SUSPENDED);

            when(importTaskRepository.findAllByStatus(ImportTaskStatus.SYNCING))
                    .thenReturn(new ArrayList<>(List.of(task)));
            when(importTaskRepository.findAllByStatus(ImportTaskStatus.CATCHING_UP))
                    .thenReturn(new ArrayList<>());
            when(databaseRepository.findById("db_test"))
                    .thenReturn(Optional.of(database));

            syncStatusCollector.collectSyncStatus();

            verify(databaseRepository).findById("db_test");
            verify(importTaskRepository, never()).save(any());
        }

        @Test
        @DisplayName("UT-SVC-SS-004: handles empty task lists")
        void handleEmptyTaskLists() {
            when(importTaskRepository.findAllByStatus(ImportTaskStatus.SYNCING))
                    .thenReturn(new ArrayList<>());
            when(importTaskRepository.findAllByStatus(ImportTaskStatus.CATCHING_UP))
                    .thenReturn(new ArrayList<>());

            syncStatusCollector.collectSyncStatus();

            verifyNoInteractions(databaseRepository);
        }

        @Test
        @DisplayName("UT-SVC-SS-005: combines SYNCING and CATCHING_UP tasks")
        void combinesSyncingAndCatchingUpTasks() {
            var task1 = new ImportTaskEntity();
            task1.setId("task_syncing");
            task1.setMode(ImportMode.SYNC);
            task1.setStatus(ImportTaskStatus.SYNCING);
            task1.setDatabaseId("db1");

            var task2 = new ImportTaskEntity();
            task2.setId("task_catchup");
            task2.setMode(ImportMode.SYNC);
            task2.setStatus(ImportTaskStatus.CATCHING_UP);
            task2.setDatabaseId("db2");

            when(importTaskRepository.findAllByStatus(ImportTaskStatus.SYNCING))
                    .thenReturn(new ArrayList<>(List.of(task1)));
            when(importTaskRepository.findAllByStatus(ImportTaskStatus.CATCHING_UP))
                    .thenReturn(new ArrayList<>(List.of(task2)));
            when(databaseRepository.findById("db1"))
                    .thenReturn(Optional.empty());
            when(databaseRepository.findById("db2"))
                    .thenReturn(Optional.empty());

            syncStatusCollector.collectSyncStatus();

            verify(databaseRepository).findById("db1");
            verify(databaseRepository).findById("db2");
        }
    }
}
