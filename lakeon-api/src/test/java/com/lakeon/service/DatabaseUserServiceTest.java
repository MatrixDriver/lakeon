package com.lakeon.service;

import com.lakeon.model.dto.*;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.DatabaseUserEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.DatabaseRole;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.DatabaseUserRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.NotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DatabaseUserService 单元测试")
class DatabaseUserServiceTest {

    @Mock
    private DatabaseUserRepository databaseUserRepository;

    @Mock
    private DatabaseRepository databaseRepository;

    @Mock
    private DatabaseService databaseService;

    @InjectMocks
    private DatabaseUserService databaseUserService;

    private TenantEntity testTenant;
    private DatabaseEntity testDatabase;

    @BeforeEach
    void setUp() {
        testTenant = new TenantEntity();
        testTenant.setId("tn_test001");

        testDatabase = new DatabaseEntity();
        testDatabase.setId("db_test001");
        testDatabase.setTenantId("tn_test001");
        testDatabase.setName("my-db");
        testDatabase.setStatus(DatabaseStatus.RUNNING);
        testDatabase.setComputeHost("10.0.0.1");
        testDatabase.setComputePort(55433);
        testDatabase.setDbUser("cloud_admin");
        testDatabase.setDbPassword("secret");
    }

    @Nested
    @DisplayName("创建用户")
    class CreateUser {

        @Test
        @DisplayName("UT-SVC-DU-001: 正常创建用户 — 保存实体并返回密码")
        void createUser_success() throws Exception {
            var request = new CreateDatabaseUserRequest("reader1", DatabaseRole.READER, null);
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));
            when(databaseUserRepository.findByDatabaseIdAndUsername("db_test001", "reader1"))
                    .thenReturn(Optional.empty());

            // Mock JDBC - use spy to intercept getConnection
            DatabaseUserService spyService = spy(databaseUserService);
            Connection mockConn = mock(Connection.class);
            Statement mockSt = mock(Statement.class);
            PreparedStatement mockPs = mock(PreparedStatement.class);
            ResultSet mockRs = mock(ResultSet.class);
            doReturn(mockConn).when(spyService).getConnection(any());
            when(mockConn.createStatement()).thenReturn(mockSt);
            when(mockSt.execute(any())).thenReturn(false);
            when(mockConn.prepareStatement("SELECT quote_ident(?)")).thenReturn(mockPs);
            when(mockPs.executeQuery()).thenReturn(mockRs);
            when(mockRs.next()).thenReturn(true);
            when(mockRs.getString(1)).thenAnswer(inv -> "\"reader1\"");

            when(databaseUserRepository.save(any(DatabaseUserEntity.class)))
                    .thenAnswer(inv -> {
                        DatabaseUserEntity entity = inv.getArgument(0);
                        entity.setId("du_test001");
                        entity.setCreatedAt(Instant.now());
                        entity.setUpdatedAt(Instant.now());
                        return entity;
                    });

            DatabaseUserCreatedResponse result = spyService.createUser(testTenant, "db_test001", request);

            assertThat(result).isNotNull();
            assertThat(result.username()).isEqualTo("reader1");
            assertThat(result.role()).isEqualTo(DatabaseRole.READER);
            assertThat(result.isOwner()).isFalse();
            assertThat(result.password()).isNotNull();
            assertThat(result.password()).hasSize(16);
            verify(databaseUserRepository).save(any(DatabaseUserEntity.class));
        }

        @Test
        @DisplayName("UT-SVC-DU-002: 用户名已存在 — 抛出 ConflictException")
        void createUser_duplicate() {
            var request = new CreateDatabaseUserRequest("reader1", DatabaseRole.READER, null);
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));
            when(databaseUserRepository.findByDatabaseIdAndUsername("db_test001", "reader1"))
                    .thenReturn(Optional.of(new DatabaseUserEntity()));

            assertThatThrownBy(() ->
                    databaseUserService.createUser(testTenant, "db_test001", request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("reader1");
        }

        @Test
        @DisplayName("UT-SVC-DU-003: 数据库不存在 — 抛出 NotFoundException")
        void createUser_dbNotFound() {
            var request = new CreateDatabaseUserRequest("reader1", DatabaseRole.READER, null);
            when(databaseRepository.findByIdAndTenantId("db_nonexist", "tn_test001"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    databaseUserService.createUser(testTenant, "db_nonexist", request))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("列出用户")
    class ListUsers {

        @Test
        @DisplayName("UT-SVC-DU-004: 正常列出 — 返回所有用户")
        void listUsers_success() {
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));

            // Mock ensureOwnerUser
            DatabaseUserEntity ownerUser = createUserEntity("du_owner", "cloud_admin", DatabaseRole.ADMIN, true);
            when(databaseUserRepository.findByDatabaseIdAndUsername("db_test001", "cloud_admin"))
                    .thenReturn(Optional.of(ownerUser));

            DatabaseUserEntity user1 = createUserEntity("du_001", "reader1", DatabaseRole.READER, false);
            DatabaseUserEntity user2 = createUserEntity("du_002", "writer1", DatabaseRole.WRITER, false);
            when(databaseUserRepository.findByDatabaseIdOrderByCreatedAtAsc("db_test001"))
                    .thenReturn(List.of(ownerUser, user1, user2));

            List<DatabaseUserResponse> result = databaseUserService.listUsers(testTenant, "db_test001");

            // cloud_admin is filtered out (internal management user), only user1 and user2 returned
            assertThat(result).hasSize(2);
            assertThat(result.get(0).username()).isEqualTo("reader1");
            assertThat(result.get(1).username()).isEqualTo("writer1");
        }

        @Test
        @DisplayName("UT-SVC-DU-005: 数据库不存在 — 抛出 NotFoundException")
        void listUsers_dbNotFound() {
            when(databaseRepository.findByIdAndTenantId("db_nonexist", "tn_test001"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    databaseUserService.listUsers(testTenant, "db_nonexist"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("修改用户角色")
    class UpdateUserRole {

        @Test
        @DisplayName("UT-SVC-DU-006: 正常修改 — 更新角色并返回")
        void updateRole_success() throws Exception {
            DatabaseUserEntity user = createUserEntity("du_001", "reader1", DatabaseRole.READER, false);
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));
            when(databaseUserRepository.findByIdAndTenantId("du_001", "tn_test001"))
                    .thenReturn(Optional.of(user));

            DatabaseUserService spyService = spy(databaseUserService);
            Connection mockConn = mock(Connection.class);
            Statement mockSt = mock(Statement.class);
            PreparedStatement mockPs = mock(PreparedStatement.class);
            ResultSet mockRs = mock(ResultSet.class);
            doReturn(mockConn).when(spyService).getConnection(any());
            when(mockConn.createStatement()).thenReturn(mockSt);
            when(mockSt.execute(any())).thenReturn(false);
            when(mockConn.prepareStatement("SELECT quote_ident(?)")).thenReturn(mockPs);
            when(mockPs.executeQuery()).thenReturn(mockRs);
            when(mockRs.next()).thenReturn(true);
            when(mockRs.getString(1)).thenReturn("\"reader1\"");

            when(databaseUserRepository.save(any(DatabaseUserEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var request = new UpdateDatabaseUserRoleRequest(DatabaseRole.WRITER);
            DatabaseUserResponse result = spyService.updateUserRole(testTenant, "db_test001", "du_001", request);

            assertThat(result.role()).isEqualTo(DatabaseRole.WRITER);
            verify(databaseUserRepository).save(any(DatabaseUserEntity.class));
        }

        @Test
        @DisplayName("UT-SVC-DU-007: 修改 owner 角色 — 抛出 BadRequestException")
        void updateRole_ownerProtected() {
            DatabaseUserEntity owner = createUserEntity("du_owner", "cloud_admin", DatabaseRole.ADMIN, true);
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));
            when(databaseUserRepository.findByIdAndTenantId("du_owner", "tn_test001"))
                    .thenReturn(Optional.of(owner));

            var request = new UpdateDatabaseUserRoleRequest(DatabaseRole.READER);
            assertThatThrownBy(() ->
                    databaseUserService.updateUserRole(testTenant, "db_test001", "du_owner", request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("owner");
        }

        @Test
        @DisplayName("UT-SVC-DU-008: 用户不存在 — 抛出 NotFoundException")
        void updateRole_userNotFound() {
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));
            when(databaseUserRepository.findByIdAndTenantId("du_nonexist", "tn_test001"))
                    .thenReturn(Optional.empty());

            var request = new UpdateDatabaseUserRoleRequest(DatabaseRole.WRITER);
            assertThatThrownBy(() ->
                    databaseUserService.updateUserRole(testTenant, "db_test001", "du_nonexist", request))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("删除用户")
    class DeleteUser {

        @Test
        @DisplayName("UT-SVC-DU-009: 正常删除 — 删除 PG role 和实体")
        void deleteUser_success() throws Exception {
            DatabaseUserEntity user = createUserEntity("du_001", "reader1", DatabaseRole.READER, false);
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));
            when(databaseUserRepository.findByIdAndTenantId("du_001", "tn_test001"))
                    .thenReturn(Optional.of(user));

            DatabaseUserService spyService = spy(databaseUserService);
            Connection mockConn = mock(Connection.class);
            Statement mockSt = mock(Statement.class);
            PreparedStatement mockPs = mock(PreparedStatement.class);
            ResultSet mockRs = mock(ResultSet.class);
            doReturn(mockConn).when(spyService).getConnection(any());
            when(mockConn.createStatement()).thenReturn(mockSt);
            when(mockSt.execute(any())).thenReturn(false);
            when(mockConn.prepareStatement("SELECT quote_ident(?)")).thenReturn(mockPs);
            when(mockPs.executeQuery()).thenReturn(mockRs);
            when(mockRs.next()).thenReturn(true);
            when(mockRs.getString(1)).thenReturn("\"reader1\"");

            spyService.deleteUser(testTenant, "db_test001", "du_001");

            verify(databaseUserRepository).delete(user);
        }

        @Test
        @DisplayName("UT-SVC-DU-010: 删除 owner — 抛出 BadRequestException")
        void deleteUser_ownerProtected() {
            DatabaseUserEntity owner = createUserEntity("du_owner", "cloud_admin", DatabaseRole.ADMIN, true);
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));
            when(databaseUserRepository.findByIdAndTenantId("du_owner", "tn_test001"))
                    .thenReturn(Optional.of(owner));

            assertThatThrownBy(() ->
                    databaseUserService.deleteUser(testTenant, "db_test001", "du_owner"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("owner");
        }
    }

    @Nested
    @DisplayName("重置密码")
    class ResetPassword {

        @Test
        @DisplayName("UT-SVC-DU-011: 正常重置 — 返回新密码")
        void resetPassword_success() throws Exception {
            DatabaseUserEntity user = createUserEntity("du_001", "reader1", DatabaseRole.READER, false);
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));
            when(databaseUserRepository.findByIdAndTenantId("du_001", "tn_test001"))
                    .thenReturn(Optional.of(user));

            DatabaseUserService spyService = spy(databaseUserService);
            Connection mockConn = mock(Connection.class);
            Statement mockSt = mock(Statement.class);
            PreparedStatement mockPs = mock(PreparedStatement.class);
            ResultSet mockRs = mock(ResultSet.class);
            doReturn(mockConn).when(spyService).getConnection(any());
            when(mockConn.createStatement()).thenReturn(mockSt);
            when(mockSt.execute(any())).thenReturn(false);
            when(mockConn.prepareStatement("SELECT quote_ident(?)")).thenReturn(mockPs);
            when(mockPs.executeQuery()).thenReturn(mockRs);
            when(mockRs.next()).thenReturn(true);
            when(mockRs.getString(1)).thenReturn("\"reader1\"");

            when(databaseUserRepository.save(any(DatabaseUserEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            DatabaseUserCreatedResponse result = spyService.resetPassword(testTenant, "db_test001", "du_001");

            assertThat(result.password()).isNotNull();
            assertThat(result.password()).hasSize(16);
            verify(databaseUserRepository).save(any(DatabaseUserEntity.class));
        }

        @Test
        @DisplayName("UT-SVC-DU-012: 重置 owner 密码 — 抛出 BadRequestException")
        void resetPassword_ownerProtected() {
            DatabaseUserEntity owner = createUserEntity("du_owner", "cloud_admin", DatabaseRole.ADMIN, true);
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));
            when(databaseUserRepository.findByIdAndTenantId("du_owner", "tn_test001"))
                    .thenReturn(Optional.of(owner));

            assertThatThrownBy(() ->
                    databaseUserService.resetPassword(testTenant, "db_test001", "du_owner"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("owner");
        }
    }

    private DatabaseUserEntity createUserEntity(String id, String username, DatabaseRole role, boolean isOwner) {
        DatabaseUserEntity entity = new DatabaseUserEntity();
        entity.setId(id);
        entity.setDatabaseId("db_test001");
        entity.setTenantId("tn_test001");
        entity.setUsername(username);
        entity.setPassword("***");
        entity.setRole(role);
        entity.setIsOwner(isOwner);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }
}
