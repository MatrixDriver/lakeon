package com.lakeon.neon.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class NeonTenant {
    @JsonProperty("id")
    private String id;

    public NeonTenant() {}

    public NeonTenant(String id) {
        this.id = id;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
