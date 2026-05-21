package com.lakeon.obs;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ManifestObjects {
    private ManifestObjects() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BranchEntry(
        @JsonProperty("branch_id") String branchId,
        @JsonProperty("parent") String parent,
        @JsonProperty("lsn") String lsn
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DatabaseEntry(
        @JsonProperty("db_id") String dbId,
        @JsonProperty("neon_tenant_id") String neonTenantId,
        @JsonProperty("name") String name,
        @JsonProperty("timeline_id") String timelineId,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("deleted_at") Instant deletedAt,
        @JsonProperty("branches") List<BranchEntry> branches
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TenantManifest(
        @JsonProperty("manifest_version") int manifestVersion,
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("owner_email") String ownerEmail,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("version") long version,
        @JsonProperty("databases") List<DatabaseEntry> databases
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OwnersIndex(
        @JsonProperty("index_version") int indexVersion,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("owners") Map<String, List<String>> owners
    ) {}
}
