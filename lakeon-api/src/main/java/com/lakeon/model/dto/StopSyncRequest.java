package com.lakeon.model.dto;

public record StopSyncRequest(boolean cleanup) {
    public StopSyncRequest() {
        this(true);
    }
}
