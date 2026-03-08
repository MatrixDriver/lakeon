package com.lakeon.model.dto;

import jakarta.validation.constraints.NotBlank;

public record RestoreFromBackupRequest(@NotBlank String name) {}
