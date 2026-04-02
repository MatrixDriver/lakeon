package com.lakeon.knowledge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KbWriteQueueRetryTest {

    @Test
    void rateLimitBackoff_firstRetry_around30s() {
        long delay = KbWriteQueue.rateLimitDelaySeconds(0);
        assertTrue(delay >= 30 && delay <= 45, "Expected 30-45, got " + delay);
    }

    @Test
    void rateLimitBackoff_secondRetry_around60s() {
        long delay = KbWriteQueue.rateLimitDelaySeconds(1);
        assertTrue(delay >= 60 && delay <= 90, "Expected 60-90, got " + delay);
    }

    @Test
    void rateLimitBackoff_thirdRetry_around120s() {
        long delay = KbWriteQueue.rateLimitDelaySeconds(2);
        assertTrue(delay >= 120 && delay <= 180, "Expected 120-180, got " + delay);
    }

    @Test
    void rateLimitBackoff_cappedAt240s() {
        long delay = KbWriteQueue.rateLimitDelaySeconds(10);
        assertTrue(delay >= 240 && delay <= 360, "Expected 240-360, got " + delay);
    }
}
