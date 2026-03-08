package com.lakeon.model.dto;

import com.lakeon.model.enums.DatabaseRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateDatabaseUserRequest(
    @NotBlank String username,
    @NotNull DatabaseRole role,
    String password
) {}
