package com.lakeon.model.dto;

import java.time.Instant;

public record CacheStatusResponse(
    boolean cached,
    Instant lastUpdated,
    boolean expired,
    long ttlSeconds
) {}
