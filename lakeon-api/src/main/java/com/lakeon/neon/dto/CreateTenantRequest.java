package com.lakeon.neon.dto;

import java.util.Map;

public class CreateTenantRequest {
    private String mode = "AttachedSingle";
    private int generation = 1;
    private Map<String, Object> tenantConf = Map.of();

    public CreateTenantRequest() {}

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public int getGeneration() { return generation; }
    public void setGeneration(int generation) { this.generation = generation; }
    public Map<String, Object> getTenantConf() { return tenantConf; }
    public void setTenantConf(Map<String, Object> tenantConf) { this.tenantConf = tenantConf; }
}
