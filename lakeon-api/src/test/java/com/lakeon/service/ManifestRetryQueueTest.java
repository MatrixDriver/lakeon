package com.lakeon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lakeon.obs.LakeonObsClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ManifestRetryQueueTest {

    @Mock LakeonObsClient obs;

    private final ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void enqueue_writesJsonBlobUnderRetryPrefix() throws Exception {
        ManifestRetryQueue queue = new ManifestRetryQueue(obs, om);

        queue.enqueue("tn1", "tenant_manifest", "boom: 503 from OBS");

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCap = ArgumentCaptor.forClass(String.class);
        verify(obs).putObject(keyCap.capture(), bodyCap.capture(), isNull());

        assertThat(keyCap.getValue()).startsWith("_retry_queue/").endsWith(".json");

        ManifestRetryQueue.RetryEntry parsed =
                om.readValue(bodyCap.getValue(), ManifestRetryQueue.RetryEntry.class);
        assertThat(parsed.tenantId()).isEqualTo("tn1");
        assertThat(parsed.kind()).isEqualTo("tenant_manifest");
        assertThat(parsed.reason()).isEqualTo("boom: 503 from OBS");
        assertThat(parsed.enqueuedAt()).isNotNull();
    }

    @Test
    void enqueue_truncatesLongReason() throws Exception {
        ManifestRetryQueue queue = new ManifestRetryQueue(obs, om);
        String huge = "x".repeat(2000);

        queue.enqueue("tn2", "owners_index", huge);

        ArgumentCaptor<String> bodyCap = ArgumentCaptor.forClass(String.class);
        verify(obs).putObject(anyString(), bodyCap.capture(), isNull());
        ManifestRetryQueue.RetryEntry parsed =
                om.readValue(bodyCap.getValue(), ManifestRetryQueue.RetryEntry.class);
        assertThat(parsed.reason()).hasSize(500);
    }

    @Test
    void enqueue_swallowsObsFailureSoOriginalErrorIsNotMasked() {
        // If the retry queue itself can't write, we MUST not throw — the caller
        // is already on the failure path and would lose the original exception.
        doThrow(new RuntimeException("OBS unavailable"))
                .when(obs).putObject(anyString(), anyString(), isNull());

        ManifestRetryQueue queue = new ManifestRetryQueue(obs, om);

        // Should not throw.
        queue.enqueue("tn3", "tenant_manifest", "original reason");

        verify(obs).putObject(anyString(), anyString(), isNull());
    }

    @Test
    void enqueue_handlesNullReason() {
        ManifestRetryQueue queue = new ManifestRetryQueue(obs, om);

        queue.enqueue("tn4", "tenant_manifest", null);

        // Must not throw on null reason; an entry should still be written.
        verify(obs).putObject(anyString(), anyString(), isNull());
        verify(obs, never()).putObject(eq(""), anyString(), isNull());
    }
}
