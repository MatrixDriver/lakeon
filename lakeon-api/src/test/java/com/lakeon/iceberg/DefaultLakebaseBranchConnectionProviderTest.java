package com.lakeon.iceberg;

import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultLakebaseBranchConnectionProviderTest {

    private final DatabaseRepository databaseRepository = mock(DatabaseRepository.class);
    private final BranchRepository branchRepository = mock(BranchRepository.class);
    private final DatabaseService databaseService = mock(DatabaseService.class);
    private final Connection connection = mock(Connection.class);
    private final TenantEntity tenant = tenant("tn_1");

    @Test
    void openWakesDatabaseRefreshesComputeAddressAndConnectsAsCloudAdmin() throws Exception {
        DatabaseEntity beforeWake = database("db_123", "tn_1", "orders", null, null);
        DatabaseEntity afterWake = database("db_123", "tn_1", "orders", "10.0.0.8", 55433);
        BranchEntity branch = branch("br_main", "db_123");
        CapturingConnectionFactory connectionFactory = new CapturingConnectionFactory(connection);
        DefaultLakebaseBranchConnectionProvider provider = new DefaultLakebaseBranchConnectionProvider(
            databaseRepository, branchRepository, databaseService, connectionFactory);

        when(databaseRepository.findByIdAndTenantId("db_123", "tn_1"))
            .thenReturn(Optional.of(beforeWake), Optional.of(afterWake));
        when(branchRepository.findByIdAndDatabaseId("br_main", "db_123")).thenReturn(Optional.of(branch));

        provider.open(tenant, "db_123", "br_main");

        verify(databaseService).wakeCompute(beforeWake);
        org.assertj.core.api.Assertions.assertThat(connectionFactory.jdbcUrl)
            .isEqualTo("jdbc:postgresql://10.0.0.8:55433/orders");
        org.assertj.core.api.Assertions.assertThat(connectionFactory.user).isEqualTo("cloud_admin");
        org.assertj.core.api.Assertions.assertThat(connectionFactory.password).isEqualTo("cloud-admin-internal");
    }

    @Test
    void openRejectsDatabaseOutsideTenant() {
        DefaultLakebaseBranchConnectionProvider provider = new DefaultLakebaseBranchConnectionProvider(
            databaseRepository, branchRepository, databaseService, new CapturingConnectionFactory(connection));

        when(databaseRepository.findByIdAndTenantId("db_123", "tn_1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.open(tenant, "db_123", "br_main"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Database not found");
    }

    @Test
    void openRejectsBranchOutsideDatabase() {
        DatabaseEntity db = database("db_123", "tn_1", "orders", "10.0.0.8", 55433);
        DefaultLakebaseBranchConnectionProvider provider = new DefaultLakebaseBranchConnectionProvider(
            databaseRepository, branchRepository, databaseService, new CapturingConnectionFactory(connection));

        when(databaseRepository.findByIdAndTenantId("db_123", "tn_1")).thenReturn(Optional.of(db));
        when(branchRepository.findByIdAndDatabaseId("br_other", "db_123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.open(tenant, "db_123", "br_other"))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Branch not found");
    }

    private static TenantEntity tenant(String id) {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(id);
        return tenant;
    }

    private static DatabaseEntity database(String id, String tenantId, String name, String host, Integer port) {
        DatabaseEntity db = new DatabaseEntity();
        db.setId(id);
        db.setTenantId(tenantId);
        db.setName(name);
        db.setStatus(DatabaseStatus.RUNNING);
        db.setComputeHost(host);
        db.setComputePort(port);
        return db;
    }

    private static BranchEntity branch(String id, String databaseId) {
        BranchEntity branch = new BranchEntity();
        branch.setId(id);
        branch.setDatabaseId(databaseId);
        return branch;
    }

    private static class CapturingConnectionFactory implements JdbcConnectionFactory {
        private final Connection connection;
        private String jdbcUrl;
        private String user;
        private String password;

        private CapturingConnectionFactory(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection open(String jdbcUrl, String user, String password) {
            this.jdbcUrl = jdbcUrl;
            this.user = user;
            this.password = password;
            return connection;
        }
    }
}
