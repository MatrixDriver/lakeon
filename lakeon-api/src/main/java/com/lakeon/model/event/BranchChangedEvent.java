package com.lakeon.model.event;

public record BranchChangedEvent(String tenantId, String dbId, String branchId, ChangeType type) {
    public enum ChangeType { CREATED, UPDATED, DELETED }
}
