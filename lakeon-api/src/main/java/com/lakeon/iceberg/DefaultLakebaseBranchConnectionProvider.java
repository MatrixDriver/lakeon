package com.lakeon.iceberg;

import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

@Component
public class DefaultLakebaseBranchConnectionProvider implements LakebaseBranchConnectionProvider {
    private static final String ADMIN_USER = "cloud_admin";
    private static final String ADMIN_PASSWORD = "cloud-admin-internal";
    private static final int DEFAULT_COMPUTE_PORT = 55433;

    private final DatabaseRepository databaseRepository;
    private final BranchRepository branchRepository;
    private final DatabaseService databaseService;
    private final JdbcConnectionFactory connectionFactory;

    public DefaultLakebaseBranchConnectionProvider(DatabaseRepository databaseRepository,
                                                   BranchRepository branchRepository,
                                                   DatabaseService databaseService,
                                                   JdbcConnectionFactory connectionFactory) {
        this.databaseRepository = databaseRepository;
        this.branchRepository = branchRepository;
        this.databaseService = databaseService;
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Connection open(TenantEntity tenant, String databaseId, String branchId) throws SQLException {
        DatabaseEntity database = databaseRepository.findByIdAndTenantId(databaseId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + databaseId));
        branchRepository.findByIdAndDatabaseId(branchId, databaseId)
            .orElseThrow(() -> new NotFoundException("Branch not found: " + branchId));

        databaseService.wakeCompute(database);
        DatabaseEntity refreshed = databaseRepository.findByIdAndTenantId(databaseId, tenant.getId())
            .orElse(database);

        String host = refreshed.getComputeHost();
        if (host == null || host.isBlank()) {
            throw new SQLException("Database compute host is not available after wake: " + databaseId);
        }
        int port = refreshed.getComputePort() != null ? refreshed.getComputePort() : DEFAULT_COMPUTE_PORT;
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + refreshed.getName();
        return connectionFactory.open(jdbcUrl, ADMIN_USER, ADMIN_PASSWORD);
    }
}
