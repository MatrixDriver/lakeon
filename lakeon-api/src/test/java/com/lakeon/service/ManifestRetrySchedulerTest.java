package com.lakeon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lakeon.obs.LakeonObsClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManifestRetrySchedulerTest {

    @Mock LakeonObsClient obs;
    @Mock ManifestWriter writer;

    private final ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());

    private LakeonObsClient.ObsGetResult entry(String tenantId, String kind, Instant enqueuedAt)
            throws Exception {
        ManifestRetryQueue.RetryEntry e =
                new ManifestRetryQueue.RetryEntry(tenantId, kind, "reason", enqueuedAt);
        return new LakeonObsClient.ObsGetResult(om.writeValueAsString(e), "etag-x");
    }

    @Test
    void retryPending_invokesWriterAndDeletesEntries() throws Exception {
        // Two entries: a tenant_manifest and an owners_index, both old enough
        // to pass the cooldown gate.
        Instant old = Instant.now().minusSeconds(120);
        when(obs.listPrefix("_retry_queue/")).thenReturn(List.of(
                new LakeonObsClient.ObsListItem("_retry_queue/1-aaa.json", 0L, "et1"),
                new LakeonObsClient.ObsListItem("_retry_queue/2-bbb.json", 0L, "et2")
        ));
        when(obs.getObject("_retry_queue/1-aaa.json"))
                .thenReturn(entry("tn1", "tenant_manifest", old));
        when(obs.getObject("_retry_queue/2-bbb.json"))
                .thenReturn(entry("tn2", "owners_index", old));

        ManifestRetryScheduler sched = new ManifestRetryScheduler(obs, om, writer);
        sched.retryPending();

        verify(writer).writeManifestForTenant("tn1");
        verify(writer).updateOwnersIndexForTenant("tn2");
        verify(obs).deleteKey("_retry_queue/1-aaa.json");
        verify(obs).deleteKey("_retry_queue/2-bbb.json");
    }

    @Test
    void retryPending_skipsEntriesFresherThanCooldown() throws Exception {
        Instant fresh = Instant.now();  // way under 30s threshold
        when(obs.listPrefix("_retry_queue/")).thenReturn(List.of(
                new LakeonObsClient.ObsListItem("_retry_queue/3-fresh.json", 0L, "et3")
        ));
        when(obs.getObject("_retry_queue/3-fresh.json"))
                .thenReturn(entry("tn-fresh", "tenant_manifest", fresh));

        ManifestRetryScheduler sched = new ManifestRetryScheduler(obs, om, writer);
        sched.retryPending();

        verify(writer, never()).writeManifestForTenant(anyString());
        verify(writer, never()).updateOwnersIndexForTenant(anyString());
        verify(obs, never()).deleteKey(anyString());
    }

    @Test
    void retryPending_dropsUnknownKindAfterLogging() throws Exception {
        Instant old = Instant.now().minusSeconds(120);
        when(obs.listPrefix("_retry_queue/")).thenReturn(List.of(
                new LakeonObsClient.ObsListItem("_retry_queue/4-x.json", 0L, "et4")
        ));
        when(obs.getObject("_retry_queue/4-x.json"))
                .thenReturn(entry("tnX", "bogus_kind", old));

        ManifestRetryScheduler sched = new ManifestRetryScheduler(obs, om, writer);
        sched.retryPending();

        verify(writer, never()).writeManifestForTenant(anyString());
        verify(writer, never()).updateOwnersIndexForTenant(anyString());
        // Unknown kind: still delete so the entry doesn't accumulate forever.
        verify(obs).deleteKey("_retry_queue/4-x.json");
    }

    @Test
    void retryPending_handlesListFailureGracefully() {
        when(obs.listPrefix("_retry_queue/")).thenThrow(new RuntimeException("OBS down"));

        ManifestRetryScheduler sched = new ManifestRetryScheduler(obs, om, writer);
        // Must not throw.
        sched.retryPending();

        verify(writer, never()).writeManifestForTenant(anyString());
        verify(writer, never()).updateOwnersIndexForTenant(anyString());
    }

    @Test
    void retryPending_leavesEntryWhenWriterThrowsUnexpectedly() throws Exception {
        Instant old = Instant.now().minusSeconds(120);
        when(obs.listPrefix("_retry_queue/")).thenReturn(List.of(
                new LakeonObsClient.ObsListItem("_retry_queue/5-err.json", 0L, "et5")
        ));
        when(obs.getObject("_retry_queue/5-err.json"))
                .thenReturn(entry("tnE", "tenant_manifest", old));
        org.mockito.Mockito.doThrow(new RuntimeException("unexpected"))
                .when(writer).writeManifestForTenant("tnE");

        ManifestRetryScheduler sched = new ManifestRetryScheduler(obs, om, writer);
        sched.retryPending();

        verify(writer).writeManifestForTenant("tnE");
        verify(obs, never()).deleteKey(anyString());
    }
}
