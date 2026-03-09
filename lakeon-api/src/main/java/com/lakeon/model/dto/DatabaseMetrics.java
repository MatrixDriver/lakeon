package com.lakeon.model.dto;

public class DatabaseMetrics {
    private double cpuUsage;       // CPU cores used
    private double cpuLimit;       // CPU cores limit
    private double memoryUsageMb;  // Memory used in MB
    private double memoryLimitMb;  // Memory limit in MB
    private int activeConnections; // Active client connections
    private int slowQueries;       // Queries running > 3s
    private double storageUsedGb;
    private double storageLimitGb;
    private String status;         // Database status

    public double getCpuUsage() { return cpuUsage; }
    public void setCpuUsage(double cpuUsage) { this.cpuUsage = cpuUsage; }

    public double getCpuLimit() { return cpuLimit; }
    public void setCpuLimit(double cpuLimit) { this.cpuLimit = cpuLimit; }

    public double getMemoryUsageMb() { return memoryUsageMb; }
    public void setMemoryUsageMb(double memoryUsageMb) { this.memoryUsageMb = memoryUsageMb; }

    public double getMemoryLimitMb() { return memoryLimitMb; }
    public void setMemoryLimitMb(double memoryLimitMb) { this.memoryLimitMb = memoryLimitMb; }

    public int getActiveConnections() { return activeConnections; }
    public void setActiveConnections(int activeConnections) { this.activeConnections = activeConnections; }

    public int getSlowQueries() { return slowQueries; }
    public void setSlowQueries(int slowQueries) { this.slowQueries = slowQueries; }

    public double getStorageUsedGb() { return storageUsedGb; }
    public void setStorageUsedGb(double storageUsedGb) { this.storageUsedGb = storageUsedGb; }

    public double getStorageLimitGb() { return storageLimitGb; }
    public void setStorageLimitGb(double storageLimitGb) { this.storageLimitGb = storageLimitGb; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
