package com.lakeon.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record TestConnectionRequest(
    @NotBlank String host,
    @NotNull Integer port,
    @NotBlank String dbname,
    @NotBlank String user,
    @NotBlank String password
) {}
