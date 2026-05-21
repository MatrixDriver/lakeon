package com.lakeon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.event.DatabaseChangedEvent;
import com.lakeon.model.event.TenantChangedEvent;
import com.lakeon.obs.LakeonObsClient;
import com.lakeon.obs.ManifestObjects;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManifestWriterTest {

    @Mock TenantRepository tenantRepo;
    @Mock DatabaseRepository dbRepo;
    @Mock BranchRepository branchRepo;
    @Mock LakeonObsClient obs;

    @Test
    void onDatabaseChanged_writesTenantManifestToObs() throws Exception {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("tn1");
        tenant.setEmail("alice@example.com");
        tenant.setCreatedAt(Instant.parse("2026-04-01T00:00:00Z"));
        when(tenantRepo.findById("tn1")).thenReturn(Optional.of(tenant));

        DatabaseEntity db = new DatabaseEntity();
        db.setId("db1");
        db.setName("mydb");
        db.setTenantId("tn1");
        db.setNeonTimelineId("tl1");
        db.setCreatedAt(Instant.parse("2026-04-05T10:00:00Z"));
        when(dbRepo.findAllByTenantId("tn1")).thenReturn(List.of(db));
        when(branchRepo.findAllByDatabaseId("db1")).thenReturn(List.of());
        when(obs.exists("tenants/tn1/_manifest.json")).thenReturn(false);
        when(obs.putObject(eq("tenants/tn1/_manifest.json"), anyString(), isNull()))
                .thenReturn("etag-1");

        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        ManifestWriter writer = new ManifestWriter(tenantRepo, dbRepo, branchRepo, obs, om, null);

        writer.onDatabaseChanged(new DatabaseChangedEvent("tn1", "db1",
                DatabaseChangedEvent.ChangeType.CREATED));

        ArgumentCaptor<String> bodyCap = ArgumentCaptor.forClass(String.class);
        verify(obs).putObject(eq("tenants/tn1/_manifest.json"), bodyCap.capture(), isNull());
        ManifestObjects.TenantManifest written = om.readValue(bodyCap.getValue(),
                ManifestObjects.TenantManifest.class);
        assertThat(written.tenantId()).isEqualTo("tn1");
        assertThat(written.ownerEmail()).isEqualTo("alice@example.com");
        assertThat(written.databases()).hasSize(1);
        assertThat(written.databases().get(0).dbId()).isEqualTo("db1");
    }

    @Test
    void onTenantChanged_updatesOwnersIndexShard() throws Exception {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("tn1");
        tenant.setEmail("alice@example.com");
        tenant.setCreatedAt(Instant.parse("2026-04-01T00:00:00Z"));
        when(tenantRepo.findById("tn1")).thenReturn(Optional.of(tenant));
        when(dbRepo.findAllByTenantId("tn1")).thenReturn(List.of());

        when(obs.exists("tenants/tn1/_manifest.json")).thenReturn(false);
        when(obs.putObject(eq("tenants/tn1/_manifest.json"), anyString(), isNull()))
                .thenReturn("etag-m");

        // Compute the expected shard for the test assertion (first byte of SHA-256(email) hex)
        String expectedShard = String.format("%02x",
                MessageDigest.getInstance("SHA-256")
                        .digest("alice@example.com".getBytes(StandardCharsets.UTF_8))[0] & 0xff);
        String expectedKey = "_global/owners/" + expectedShard + ".idx";
        when(obs.exists(expectedKey)).thenReturn(false);
        when(obs.putObject(eq(expectedKey), anyString(), isNull())).thenReturn("etag-o");

        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        ManifestWriter writer = new ManifestWriter(tenantRepo, dbRepo, branchRepo, obs, om, null);

        writer.onTenantChanged(new TenantChangedEvent("tn1", TenantChangedEvent.ChangeType.CREATED));

        ArgumentCaptor<String> bodyCap = ArgumentCaptor.forClass(String.class);
        verify(obs).putObject(eq(expectedKey), bodyCap.capture(), isNull());
        ManifestObjects.OwnersIndex idx = om.readValue(bodyCap.getValue(),
                ManifestObjects.OwnersIndex.class);
        assertThat(idx.owners()).containsKey("alice@example.com");
        assertThat(idx.owners().get("alice@example.com")).contains("tn1");
    }
}
