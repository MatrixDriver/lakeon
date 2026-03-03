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

    public TenantResponse() {}

    public TenantResponse(String id, String name, String apiKey, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.apiKey = apiKey;
        this.createdAt = createdAt;
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

    public static class Builder {
        private String id;
        private String name;
        private String apiKey;
        private Instant createdAt;

        public Builder id(String id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public TenantResponse build() {
            return new TenantResponse(id, name, apiKey, createdAt);
        }
    }
}
