package com.lakeon.model.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum DatabaseStatus {
    CREATING, RUNNING, SUSPENDED, ERROR, DELETING;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
