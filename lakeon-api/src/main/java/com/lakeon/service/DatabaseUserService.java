package com.lakeon.service;

import com.lakeon.model.dto.*;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.DatabaseUserEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.DatabaseRole;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.DatabaseUserRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.NotFoundException;
import com.lakeon.service.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.*;
import java.util.List;

@Service
public class DatabaseUserService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseUserService.class);

    private final DatabaseUserRepository databaseUserRepository;
    private final DatabaseRepository databaseRepository;
    private final DatabaseService databaseService;

    public DatabaseUserService(DatabaseUserRepository databaseUserRepository,
                               DatabaseRepository databaseRepository,
                               DatabaseService databaseService) {
        this.databaseUserRepository = databaseUserRepository;
        this.databaseRepository = databaseRepository;
        this.databaseService = databaseService;
    }

    @Transactional
    public DatabaseUserCreatedResponse createUser(TenantEntity tenant, String dbId, CreateDatabaseUserRequest request) {
        DatabaseEntity db = findDatabase(tenant, dbId);

        // Check for duplicate username
        databaseUserRepository.findByDatabaseIdAndUsername(dbId, request.username()).ifPresent(existing -> {
            throw new ConflictException("User '" + request.username() + "' already exists for this database");
        });

        // Generate password if not provided
        String rawPassword = (request.password() != null && !request.password().isBlank())
                ? request.password() : generatePassword();

        // Execute SQL on the target database to create the PG role
        try (Connection conn = getConnection(db)) {
            createPgRole(conn, request.username(), rawPassword);
            grantPermissions(conn, db.getName(), request.username(), request.role());
        } catch (SQLException e) {
            throw new ServiceException("Failed to create database user: " + e.getMessage(), e);
        }

        // Save entity
        DatabaseUserEntity entity = new DatabaseUserEntity();
        entity.setDatabaseId(dbId);
        entity.setTenantId(tenant.getId());
        entity.setUsername(request.username());
        entity.setPassword("***"); // Do not store raw password
        entity.setRole(request.role());
        entity.setIsOwner(false);

        entity = databaseUserRepository.save(entity);
        log.info("Created user {} with role {} for database {}", request.username(), request.role(), dbId);

        DatabaseUserResponse base = toResponse(entity);
        return DatabaseUserCreatedResponse.from(base, rawPassword);
    }

    public List<DatabaseUserResponse> listUsers(TenantEntity tenant, String dbId) {
        findDatabase(tenant, dbId);

        // Ensure owner user entity exists
        ensureOwnerUser(tenant, dbId);

        return databaseUserRepository.findByDatabaseIdOrderByCreatedAtAsc(dbId).stream()
                .filter(u -> !"cloud_admin".equals(u.getUsername()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public DatabaseUserResponse updateUserRole(TenantEntity tenant, String dbId, String userId,
                                                UpdateDatabaseUserRoleRequest request) {
        DatabaseEntity db = findDatabase(tenant, dbId);
        DatabaseUserEntity user = findUser(tenant, dbId, userId);

        if (user.getIsOwner()) {
            throw new BadRequestException("Cannot modify the owner user's role");
        }

        DatabaseRole oldRole = user.getRole();
        DatabaseRole newRole = request.role();

        // Update PG role grants
        try (Connection conn = getConnection(db)) {
            revokePermissions(conn, db.getName(), user.getUsername(), oldRole);
            grantPermissions(conn, db.getName(), user.getUsername(), newRole);
        } catch (SQLException e) {
            throw new ServiceException("Failed to update user role: " + e.getMessage(), e);
        }

        user.setRole(newRole);
        user = databaseUserRepository.save(user);
        log.info("Updated user {} role from {} to {} for database {}", user.getUsername(), oldRole, newRole, dbId);

        return toResponse(user);
    }

    @Transactional
    public void deleteUser(TenantEntity tenant, String dbId, String userId) {
        DatabaseEntity db = findDatabase(tenant, dbId);
        DatabaseUserEntity user = findUser(tenant, dbId, userId);

        if (user.getIsOwner()) {
            throw new BadRequestException("Cannot delete the owner user");
        }

        // Drop PG role
        try (Connection conn = getConnection(db)) {
            revokePermissions(conn, db.getName(), user.getUsername(), user.getRole());
            dropPgRole(conn, user.getUsername());
        } catch (SQLException e) {
            log.warn("Failed to drop PG role {} for database {}: {}", user.getUsername(), dbId, e.getMessage());
        }

        databaseUserRepository.delete(user);
        log.info("Deleted user {} from database {}", user.getUsername(), dbId);
    }

    @Transactional
    public DatabaseUserCreatedResponse resetPassword(TenantEntity tenant, String dbId, String userId) {
        DatabaseEntity db = findDatabase(tenant, dbId);
        DatabaseUserEntity user = findUser(tenant, dbId, userId);

        if (user.getIsOwner()) {
            throw new BadRequestException("Cannot reset the owner user's password. Use the database reset-password endpoint instead.");
        }

        String newPassword = generatePassword();

        // Alter PG role password
        try (Connection conn = getConnection(db)) {
            alterPgRolePassword(conn, user.getUsername(), newPassword);
        } catch (SQLException e) {
            throw new ServiceException("Failed to reset user password: " + e.getMessage(), e);
        }

        user.setPassword("***");
        user = databaseUserRepository.save(user);
        log.info("Reset password for user {} in database {}", user.getUsername(), dbId);

        DatabaseUserResponse base = toResponse(user);
        return DatabaseUserCreatedResponse.from(base, newPassword);
    }

    /**
     * Ensure the owner (cloud_admin) user entity exists for this database.
     * Called lazily on first access of the users tab.
     */
    private void ensureOwnerUser(TenantEntity tenant, String dbId) {
        DatabaseEntity db = databaseRepository.findByIdAndTenantId(dbId, tenant.getId()).orElse(null);
        if (db == null) return;

        // 确保默认用户（创建数据库时自动生成的）在列表中
        if (db.getDbUser() != null && databaseUserRepository.findByDatabaseIdAndUsername(dbId, db.getDbUser()).isEmpty()) {
            DatabaseUserEntity defaultUser = new DatabaseUserEntity();
            defaultUser.setDatabaseId(dbId);
            defaultUser.setTenantId(tenant.getId());
            defaultUser.setUsername(db.getDbUser());
            defaultUser.setPassword("***");
            defaultUser.setRole(DatabaseRole.ADMIN);
            defaultUser.setIsOwner(true);
            databaseUserRepository.save(defaultUser);
            log.info("Created default user entity '{}' for database {}", db.getDbUser(), dbId);
        }

        // 确保 cloud_admin 内部管理用户在列表中
        if (databaseUserRepository.findByDatabaseIdAndUsername(dbId, "cloud_admin").isEmpty()) {
            DatabaseUserEntity owner = new DatabaseUserEntity();
            owner.setDatabaseId(dbId);
            owner.setTenantId(tenant.getId());
            owner.setUsername("cloud_admin");
            owner.setPassword("***");
            owner.setRole(DatabaseRole.ADMIN);
            owner.setIsOwner(false);
            databaseUserRepository.save(owner);
            log.info("Created cloud_admin user entity for database {}", dbId);
        }
    }

    // ---- SQL execution helpers ----

    private void createPgRole(Connection conn, String username, String password) throws SQLException {
        String sql = "CREATE ROLE " + quoteIdentifier(conn, username)
                + " WITH LOGIN PASSWORD " + quoteLiteral(password);
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private void dropPgRole(Connection conn, String username) throws SQLException {
        String sql = "DROP ROLE IF EXISTS " + quoteIdentifier(conn, username);
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private void alterPgRolePassword(Connection conn, String username, String newPassword) throws SQLException {
        String sql = "ALTER ROLE " + quoteIdentifier(conn, username)
                + " WITH PASSWORD " + quoteLiteral(newPassword);
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private void grantPermissions(Connection conn, String dbName, String username, DatabaseRole role) throws SQLException {
        String quotedUser = quoteIdentifier(conn, username);
        try (Statement st = conn.createStatement()) {
            switch (role) {
                case READER:
                    st.execute("GRANT CONNECT ON DATABASE " + quoteIdentifier(conn, dbName) + " TO " + quotedUser);
                    st.execute("GRANT USAGE ON SCHEMA public TO " + quotedUser);
                    st.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + quotedUser);
                    st.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO " + quotedUser);
                    break;
                case WRITER:
                    st.execute("GRANT CONNECT ON DATABASE " + quoteIdentifier(conn, dbName) + " TO " + quotedUser);
                    st.execute("GRANT USAGE ON SCHEMA public TO " + quotedUser);
                    st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + quotedUser);
                    st.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + quotedUser);
                    break;
                case ADMIN:
                    st.execute("GRANT ALL PRIVILEGES ON DATABASE " + quoteIdentifier(conn, dbName) + " TO " + quotedUser);
                    st.execute("GRANT ALL PRIVILEGES ON SCHEMA public TO " + quotedUser);
                    st.execute("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO " + quotedUser);
                    st.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO " + quotedUser);
                    break;
            }
        }
    }

    private void revokePermissions(Connection conn, String dbName, String username, DatabaseRole role) throws SQLException {
        String quotedUser = quoteIdentifier(conn, username);
        try (Statement st = conn.createStatement()) {
            switch (role) {
                case READER:
                    st.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT ON TABLES FROM " + quotedUser);
                    st.execute("REVOKE SELECT ON ALL TABLES IN SCHEMA public FROM " + quotedUser);
                    st.execute("REVOKE USAGE ON SCHEMA public FROM " + quotedUser);
                    st.execute("REVOKE CONNECT ON DATABASE " + quoteIdentifier(conn, dbName) + " FROM " + quotedUser);
                    break;
                case WRITER:
                    st.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT, INSERT, UPDATE, DELETE ON TABLES FROM " + quotedUser);
                    st.execute("REVOKE SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public FROM " + quotedUser);
                    st.execute("REVOKE USAGE ON SCHEMA public FROM " + quotedUser);
                    st.execute("REVOKE CONNECT ON DATABASE " + quoteIdentifier(conn, dbName) + " FROM " + quotedUser);
                    break;
                case ADMIN:
                    st.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL PRIVILEGES ON TABLES FROM " + quotedUser);
                    st.execute("REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM " + quotedUser);
                    st.execute("REVOKE ALL PRIVILEGES ON SCHEMA public FROM " + quotedUser);
                    st.execute("REVOKE ALL PRIVILEGES ON DATABASE " + quoteIdentifier(conn, dbName) + " FROM " + quotedUser);
                    break;
            }
        }
    }

    // ---- Connection and helper methods ----

    Connection getConnection(DatabaseEntity db) throws SQLException {
        // Ensure compute is running
        databaseService.wakeCompute(db);

        // Re-read entity in case wakeCompute updated host/port
        if (db.getComputeHost() == null) {
            DatabaseEntity refreshed = databaseRepository.findById(db.getId()).orElse(db);
            db.setComputeHost(refreshed.getComputeHost());
            db.setComputePort(refreshed.getComputePort());
            db.setComputePodName(refreshed.getComputePodName());
            db.setStatus(refreshed.getStatus());
        }

        String host = db.getComputeHost();
        int port = db.getComputePort() != null ? db.getComputePort() : 55433;
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db.getName();

        Connection conn = null;
        int maxRetries = 5;
        for (int i = 0; i < maxRetries; i++) {
            try {
                conn = DriverManager.getConnection(jdbcUrl, "cloud_admin", "cloud-admin-internal");
                break;
            } catch (SQLException e) {
                if (i == maxRetries - 1) throw e;
                log.debug("JDBC connection attempt {} failed, retrying in 2s: {}", i + 1, e.getMessage());
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
        return conn;
    }

    private DatabaseEntity findDatabase(TenantEntity tenant, String dbId) {
        return databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));
    }

    private DatabaseUserEntity findUser(TenantEntity tenant, String dbId, String userId) {
        DatabaseUserEntity user = databaseUserRepository.findByIdAndTenantId(userId, tenant.getId())
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        if (!user.getDatabaseId().equals(dbId)) {
            throw new NotFoundException("User not found: " + userId);
        }
        return user;
    }

    private DatabaseUserResponse toResponse(DatabaseUserEntity entity) {
        return new DatabaseUserResponse(
                entity.getId(),
                entity.getDatabaseId(),
                entity.getUsername(),
                entity.getRole(),
                entity.getIsOwner(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String generatePassword() {
        SecureRandom random = new SecureRandom();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String quoteIdentifier(Connection conn, String identifier) {
        try {
            try (PreparedStatement ps = conn.prepareStatement("SELECT quote_ident(?)")) {
                ps.setString(1, identifier);
                ResultSet rs = ps.executeQuery();
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            return "\"" + identifier.replace("\"", "\"\"") + "\"";
        }
    }

    private String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
