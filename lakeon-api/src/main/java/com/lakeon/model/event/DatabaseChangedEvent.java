package com.lakeon.model.event;

public record DatabaseChangedEvent(String tenantId, String dbId, ChangeType type) {
    public enum ChangeType { CREATED, UPDATED, DELETED }
}
