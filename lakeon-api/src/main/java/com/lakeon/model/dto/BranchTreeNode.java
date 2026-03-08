package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record BranchTreeNode(
    String id,
    String name,
    @JsonProperty("parent_branch_id") String parentBranchId,
    @JsonProperty("is_default") boolean isDefault,
    @JsonProperty("ancestor_lsn") String ancestorLsn,
    @JsonProperty("last_record_lsn") String lastRecordLsn,
    @JsonProperty("current_logical_size_bytes") Long currentLogicalSizeBytes,
    @JsonProperty("created_at") Instant createdAt
) {}
