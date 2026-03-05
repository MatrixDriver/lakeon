package com.lakeon.model.dto;

import jakarta.validation.constraints.NotBlank;

public record ExecuteQueryRequest(@NotBlank String sql) {}
