package com.lakeon.model.dto;

import java.time.Instant;

public record CacheRefreshResponse(
    String message,
    Instant refreshedAt,
    int schemasCount,
    int tablesCount
) {}
