package com.lakeon.service;

import com.lakeon.model.dto.CreateTenantRequest;
import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.entity.ApiKeyEntity;
import com.lakeon.config.LakeonProperties;
import com.lakeon.repository.ApiKeyRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.InviteCodeRepository;
import com.lakeon.repository.TenantRepository;
import com.lakeon.model.entity.InviteCodeEntity;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.NotFoundException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantService 单元测试")
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private DatabaseRepository databaseRepository;

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private InviteCodeRepository inviteCodeRepository;

    @Mock
    private LakeonProperties props;

    @Mock
    private ApplicationEventPublisher events;

    @InjectMocks
    private TenantService tenantService;

    @Test
    @DisplayName("UT-SVC-TN-001: 创建租户 — 生成唯一 API Key，保存元数据")
    void createTenant_success() {
        // Given
        var request = new CreateTenantRequest("test-user", "password123", null);
        when(props.getInviteRequired()).thenReturn(false);
        when(tenantRepository.findByUsername("test-user"))
                .thenReturn(Optional.empty());
        when(tenantRepository.save(any(TenantEntity.class)))
                .thenAnswer(inv -> {
                    TenantEntity entity = inv.getArgument(0);
                    // Simulate @PrePersist behavior since mocks don't trigger JPA lifecycle
                    entity.prePersist();
                    return entity;
                });
        when(apiKeyRepository.save(any(ApiKeyEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        var result = tenantService.create(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).startsWith("tn_");
        assertThat(result.getApiKey()).isNotBlank();
        assertThat(result.getApiKey().length()).isGreaterThanOrEqualTo(32);

        ArgumentCaptor<TenantEntity> captor = ArgumentCaptor.forClass(TenantEntity.class);
        verify(tenantRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("test-user");
    }

    @Test
    @DisplayName("UT-SVC-TN-002: 查看租户信息 — 返回租户详情")
    void getTenant_success() {
        // Given
        var tenant = new TenantEntity();
        tenant.setId("tn_get001");
        tenant.setName("my-tenant");
        tenant.setApiKey("api-key-xxxxx");
        when(tenantRepository.findById("tn_get001"))
                .thenReturn(Optional.of(tenant));
        when(databaseRepository.findAllByTenantId("tn_get001"))
                .thenReturn(List.of());

        // When
        var result = tenantService.get("tn_get001");

        // Then
        assertThat(result.getId()).isEqualTo("tn_get001");
        assertThat(result.getName()).isEqualTo("my-tenant");
    }

    @Test
    @DisplayName("UT-SVC-TN-003: 租户不存在 — 抛出 NotFoundException")
    void getTenant_notFound() {
        // Given
        when(tenantRepository.findById("tn_nonexist"))
                .thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> tenantService.get("tn_nonexist"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("UT-SVC-TN-004: 重新生成 API Key — 返回新密钥")
    void regenerateApiKey_shouldGenerateNewKey() {
        // Given
        String oldApiKey = "lk_abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
        var tenant = new TenantEntity();
        tenant.setId("tn_regen01");
        tenant.setName("regen-tenant");
        tenant.setApiKey(oldApiKey);

        when(tenantRepository.findById("tn_regen01"))
                .thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(TenantEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        TenantResponse result = tenantService.regenerateApiKey("tn_regen01");

        // Then
        assertThat(result.getApiKey()).isNotEqualTo(oldApiKey);
        assertThat(result.getApiKey()).startsWith("lk_");
        assertThat(result.getApiKey()).hasSize(67); // "lk_" + 64 hex chars

        verify(tenantRepository).save(any(TenantEntity.class));
    }

    @Test
    @DisplayName("UT-SVC-TN-005: 邀请码模式 — 无邀请码拒绝注册")
    void createTenant_inviteRequired_noCode() {
        when(props.getInviteRequired()).thenReturn(true);
        var request = new CreateTenantRequest("newuser", "pass1234", null);

        assertThatThrownBy(() -> tenantService.create(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invite code is required");
    }

    @Test
    @DisplayName("UT-SVC-TN-006: 邀请码模式 — 无效邀请码拒绝注册")
    void createTenant_inviteRequired_invalidCode() {
        when(props.getInviteRequired()).thenReturn(true);
        when(inviteCodeRepository.findById("BADCODE1")).thenReturn(Optional.empty());
        var request = new CreateTenantRequest("newuser", "pass1234", "BADCODE1");

        assertThatThrownBy(() -> tenantService.create(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid or expired");
    }

    @Test
    @DisplayName("UT-SVC-TN-007: 邀请码模式 — 有效邀请码允许注册并递增用量")
    void createTenant_inviteRequired_validCode() {
        when(props.getInviteRequired()).thenReturn(true);
        InviteCodeEntity invite = new InviteCodeEntity();
        invite.setCode("GOODCODE");
        invite.setMaxUses(10);
        invite.setUsedCount(0);
        when(inviteCodeRepository.findById("GOODCODE")).thenReturn(Optional.of(invite));
        when(tenantRepository.findByUsername("newuser")).thenReturn(Optional.empty());
        when(tenantRepository.save(any(TenantEntity.class))).thenAnswer(inv -> {
            TenantEntity e = inv.getArgument(0);
            e.prePersist();
            return e;
        });
        when(apiKeyRepository.save(any(ApiKeyEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inviteCodeRepository.save(any(InviteCodeEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateTenantRequest("newuser", "pass1234", "GOODCODE");
        var result = tenantService.create(request);

        assertThat(result).isNotNull();
        assertThat(result.getId()).startsWith("tn_");
        assertThat(invite.getUsedCount()).isEqualTo(1);
        verify(inviteCodeRepository).save(invite);
    }

    @Test
    @DisplayName("UT-SVC-TN-008: 邀请码模式 — 用尽的邀请码拒绝注册")
    void createTenant_inviteRequired_exhaustedCode() {
        when(props.getInviteRequired()).thenReturn(true);
        InviteCodeEntity invite = new InviteCodeEntity();
        invite.setCode("USEDCODE");
        invite.setMaxUses(1);
        invite.setUsedCount(1);
        when(inviteCodeRepository.findById("USEDCODE")).thenReturn(Optional.of(invite));

        var request = new CreateTenantRequest("newuser", "pass1234", "USEDCODE");
        assertThatThrownBy(() -> tenantService.create(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid or expired");
    }
}
