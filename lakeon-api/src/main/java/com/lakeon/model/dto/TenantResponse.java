package com.lakeon.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public class TenantResponse {
    private String id;
    private String name;
    @JsonProperty("api_key")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String apiKey;
    @JsonProperty("created_at")
    private Instant createdAt;
    @JsonProperty("max_databases")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer maxDatabases;
    @JsonProperty("max_storage_gb")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer maxStorageGb;
    @JsonProperty("max_compute_cu")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer maxComputeCu;
    @JsonProperty("database_count")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer databaseCount;

    public TenantResponse() {}

    public TenantResponse(String id, String name, String apiKey, Instant createdAt,
                          Integer maxDatabases, Integer maxStorageGb, Integer maxComputeCu,
                          Integer databaseCount) {
        this.id = id;
        this.name = name;
        this.apiKey = apiKey;
        this.createdAt = createdAt;
        this.maxDatabases = maxDatabases;
        this.maxStorageGb = maxStorageGb;
        this.maxComputeCu = maxComputeCu;
        this.databaseCount = databaseCount;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Integer getMaxDatabases() { return maxDatabases; }
    public void setMaxDatabases(Integer maxDatabases) { this.maxDatabases = maxDatabases; }
    public Integer getMaxStorageGb() { return maxStorageGb; }
    public void setMaxStorageGb(Integer maxStorageGb) { this.maxStorageGb = maxStorageGb; }
    public Integer getMaxComputeCu() { return maxComputeCu; }
    public void setMaxComputeCu(Integer maxComputeCu) { this.maxComputeCu = maxComputeCu; }
    public Integer getDatabaseCount() { return databaseCount; }
    public void setDatabaseCount(Integer databaseCount) { this.databaseCount = databaseCount; }

    public static class Builder {
        private String id;
        private String name;
        private String apiKey;
        private Instant createdAt;
        private Integer maxDatabases;
        private Integer maxStorageGb;
        private Integer maxComputeCu;
        private Integer databaseCount;

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder maxDatabases(Integer maxDatabases) { this.maxDatabases = maxDatabases; return this; }
        public Builder maxStorageGb(Integer maxStorageGb) { this.maxStorageGb = maxStorageGb; return this; }
        public Builder maxComputeCu(Integer maxComputeCu) { this.maxComputeCu = maxComputeCu; return this; }
        public Builder databaseCount(Integer databaseCount) { this.databaseCount = databaseCount; return this; }

        public TenantResponse build() {
            return new TenantResponse(id, name, apiKey, createdAt, maxDatabases, maxStorageGb, maxComputeCu, databaseCount);
        }
    }
}
