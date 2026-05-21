package com.lakeon.model.event;

public record TenantChangedEvent(String tenantId, ChangeType type) {
    public enum ChangeType { CREATED, UPDATED, DELETED }
}
