package com.lakeon.model.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateTenantRequest(
    @NotBlank String username,
    @NotBlank String password
) {}
