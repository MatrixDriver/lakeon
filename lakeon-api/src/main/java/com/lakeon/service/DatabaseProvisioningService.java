package com.lakeon.service;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.BranchStatus;
import com.lakeon.model.enums.ComputeStatus;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

@Service
public class DatabaseProvisioningService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseProvisioningService.class);

    private final DatabaseRepository databaseRepository;
    private final BranchRepository branchRepository;
    private final ComputePodManager computePodManager;
    private final OperationLogService operationLogService;
    private final DatabaseService databaseService;
    private final TransactionTemplate txTemplate;

    public DatabaseProvisioningService(DatabaseRepository databaseRepository,
                                        BranchRepository branchRepository,
                                        ComputePodManager computePodManager,
                                        OperationLogService operationLogService,
                                        @org.springframework.context.annotation.Lazy DatabaseService databaseService,
                                        TransactionTemplate txTemplate) {
        this.databaseRepository = databaseRepository;
        this.branchRepository = branchRepository;
        this.computePodManager = computePodManager;
        this.operationLogService = operationLogService;
        this.databaseService = databaseService;
        this.txTemplate = txTemplate;
    }

    @Async("databaseCreateExecutor")
    public void provisionAsync(String databaseId, String neonTimelineId,
                                String dbUser, String opLogId) {
        try {
            // Step 1: Create compute pod
            updateStatusMessage(databaseId, "正在启动计算节点（如需扩容节点可能需要1~2分钟）...");
            DatabaseEntity entity = databaseRepository.findById(databaseId).orElseThrow();
            computePodManager.createComputePod(entity);
            boolean ready = computePodManager.waitForPodReady(entity.getComputePodName(), 180_000);
            if (!ready) {
                throw new RuntimeException("计算节点启动超时(180s)，可能需要等待弹性节点扩容");
            }

            // Step 2: Enable extensions
            updateStatusMessage(databaseId, "正在配置默认扩展...");
            entity = databaseRepository.findById(databaseId).orElseThrow();
            databaseService.enableDefaultExtensions(entity);

            // Step 3: Finalize — set RUNNING
            txTemplate.executeWithoutResult(status -> {
                DatabaseEntity e = databaseRepository.findById(databaseId).orElseThrow();
                e.setConnectionUri(databaseService.buildConnectionUri(dbUser, e.getName()));
                e.setStatus(DatabaseStatus.RUNNING);
                e.setStatusMessage(null);
                e.setLastActiveAt(Instant.now());
                databaseRepository.save(e);

                BranchEntity mainBranch = new BranchEntity();
                mainBranch.setName("main");
                mainBranch.setDatabaseId(e.getId());
                mainBranch.setNeonTimelineId(neonTimelineId);
                mainBranch.setIsDefault(true);
                mainBranch.setStatus(BranchStatus.ACTIVE);
                mainBranch.setComputePodName(e.getComputePodName());
                mainBranch.setComputeHost(e.getComputeHost());
                mainBranch.setComputePort(e.getComputePort());
                mainBranch.setComputeStatus(ComputeStatus.RUNNING);
                mainBranch.setSuspendTimeout(e.getSuspendTimeout());
                mainBranch.setLastActiveAt(Instant.now());
                branchRepository.save(mainBranch);
            });

            // Complete operation log as success
            operationLogService.findById(opLogId).ifPresent(opLog ->
                operationLogService.completeOperation(opLog, null));
            log.info("Database {} provisioned successfully", databaseId);

        } catch (Exception e) {
            log.error("Failed to provision database {}: {}", databaseId, e.getMessage(), e);
            // Set ERROR status with message
            try {
                txTemplate.executeWithoutResult(status -> {
                    DatabaseEntity db = databaseRepository.findById(databaseId).orElse(null);
                    if (db != null) {
                        db.setStatus(DatabaseStatus.ERROR);
                        db.setStatusMessage(e.getMessage());
                        databaseRepository.save(db);
                    }
                });
            } catch (Exception updateEx) {
                log.error("Failed to update database {} status to ERROR: {}", databaseId, updateEx.getMessage());
            }
            // Complete operation log as failed
            try {
                operationLogService.findById(opLogId).ifPresent(opLog ->
                    operationLogService.completeOperation(opLog, e.getMessage()));
            } catch (Exception logEx) {
                log.error("Failed to complete operation log: {}", logEx.getMessage());
            }
        }
    }

    private void updateStatusMessage(String databaseId, String message) {
        txTemplate.executeWithoutResult(status -> {
            DatabaseEntity e = databaseRepository.findById(databaseId).orElseThrow();
            e.setStatusMessage(message);
            databaseRepository.save(e);
        });
    }

    /**
     * Called on app startup to mark any stuck CREATING databases as ERROR.
     */
    @jakarta.annotation.PostConstruct
    public void onStartup() {
        var stuck = databaseRepository.findAllByStatus(DatabaseStatus.CREATING);
        for (DatabaseEntity db : stuck) {
            log.warn("Database {} stuck in CREATING status, marking as ERROR", db.getId());
            db.setStatus(DatabaseStatus.ERROR);
            db.setStatusMessage("创建过程被服务重启中断，请删除后重新创建");
            databaseRepository.save(db);
        }
        if (!stuck.isEmpty()) {
            log.info("Recovered {} stuck CREATING databases", stuck.size());
        }
    }
}
