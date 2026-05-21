package com.lakeon.service;

import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.TenantRepository;
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
@DisplayName("TenantService 禁用/启用 单元测试")
class TenantServiceDisableTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private DatabaseRepository databaseRepository;

    @Mock
    private ApplicationEventPublisher events;

    @InjectMocks
    private TenantService tenantService;

    @Test
    @DisplayName("UT-SVC-TN-005: 禁用租户 — disabled=true, disabledAt 有值")
    void disableTenant_success() {
        // Given
        var tenant = buildTenant("tn_dis001", "tenant-a", false);
        when(tenantRepository.findById("tn_dis001")).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(TenantEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(databaseRepository.findAllByTenantId("tn_dis001")).thenReturn(List.of());

        // When
        TenantResponse result = tenantService.disableTenant("tn_dis001");

        // Then
        assertThat(result.getDisabled()).isTrue();
        assertThat(result.getDisabledAt()).isNotNull();

        ArgumentCaptor<TenantEntity> captor = ArgumentCaptor.forClass(TenantEntity.class);
        verify(tenantRepository).save(captor.capture());
        assertThat(captor.getValue().getDisabled()).isTrue();
        assertThat(captor.getValue().getDisabledAt()).isNotNull();
    }

    @Test
    @DisplayName("UT-SVC-TN-006: 启用租户 — disabled=false, disabledAt 清空")
    void enableTenant_success() {
        // Given
        var tenant = buildTenant("tn_en001", "tenant-b", true);
        when(tenantRepository.findById("tn_en001")).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any(TenantEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(databaseRepository.findAllByTenantId("tn_en001")).thenReturn(List.of());

        // When
        TenantResponse result = tenantService.enableTenant("tn_en001");

        // Then
        assertThat(result.getDisabled()).isFalse();
        assertThat(result.getDisabledAt()).isNull();

        ArgumentCaptor<TenantEntity> captor = ArgumentCaptor.forClass(TenantEntity.class);
        verify(tenantRepository).save(captor.capture());
        assertThat(captor.getValue().getDisabled()).isFalse();
        assertThat(captor.getValue().getDisabledAt()).isNull();
    }

    @Test
    @DisplayName("UT-SVC-TN-007: 禁用不存在的租户 — 抛出 NotFoundException")
    void disableTenant_notFound() {
        when(tenantRepository.findById("tn_ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.disableTenant("tn_ghost"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("UT-SVC-TN-008: 启用不存在的租户 — 抛出 NotFoundException")
    void enableTenant_notFound() {
        when(tenantRepository.findById("tn_ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.enableTenant("tn_ghost"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("UT-SVC-TN-009: listAll 包含 disabled 字段")
    void listAll_includesDisabledField() {
        var t1 = buildTenant("tn_l001", "active-tenant", false);
        var t2 = buildTenant("tn_l002", "disabled-tenant", true);
        when(tenantRepository.findAll()).thenReturn(List.of(t1, t2));
        when(databaseRepository.findAllByTenantId(any())).thenReturn(List.of());

        var result = tenantService.listAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getDisabled()).isFalse();
        assertThat(result.get(1).getDisabled()).isTrue();
    }

    @Test
    @DisplayName("UT-SVC-TN-010: get 包含 disabled 字段")
    void get_includesDisabledField() {
        var tenant = buildTenant("tn_g001", "my-tenant", true);
        when(tenantRepository.findById("tn_g001")).thenReturn(Optional.of(tenant));
        when(databaseRepository.findAllByTenantId("tn_g001")).thenReturn(List.of());

        var result = tenantService.get("tn_g001");

        assertThat(result.getDisabled()).isTrue();
        assertThat(result.getDisabledAt()).isNotNull();
    }

    private TenantEntity buildTenant(String id, String name, boolean disabled) {
        var tenant = new TenantEntity();
        tenant.setId(id);
        tenant.setName(name);
        tenant.setApiKey("lk_test");
        tenant.setDisabled(disabled);
        if (disabled) {
            tenant.setDisabledAt(java.time.Instant.now());
        }
        return tenant;
    }
}
