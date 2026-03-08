package com.lakeon.model.dto;

import com.lakeon.model.enums.DatabaseRole;
import jakarta.validation.constraints.NotNull;

public record UpdateDatabaseUserRoleRequest(
    @NotNull DatabaseRole role
) {}
