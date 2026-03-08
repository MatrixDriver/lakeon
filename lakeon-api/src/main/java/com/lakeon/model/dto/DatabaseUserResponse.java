package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lakeon.model.enums.DatabaseRole;

import java.time.Instant;

public record DatabaseUserResponse(
    String id,
    @JsonProperty("database_id") String databaseId,
    String username,
    DatabaseRole role,
    @JsonProperty("is_owner") Boolean isOwner,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt
) {}
