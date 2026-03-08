package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateAuditConfigRequest(
    Boolean enabled,
    @JsonProperty("log_ddl") Boolean logDdl,
    @JsonProperty("log_dml") Boolean logDml,
    @JsonProperty("log_select") Boolean logSelect,
    @JsonProperty("retention_days") Integer retentionDays
) {}
