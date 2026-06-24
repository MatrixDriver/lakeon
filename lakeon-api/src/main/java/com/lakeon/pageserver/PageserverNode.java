package com.lakeon.pageserver;

public record PageserverNode(String id, String httpUrl, String pgHost, int pgPort) {

    public PageserverNode {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("pageserver node id is required");
        }
        if (httpUrl == null || httpUrl.isBlank()) {
            throw new IllegalArgumentException("pageserver httpUrl is required");
        }
        if (pgHost == null || pgHost.isBlank()) {
            throw new IllegalArgumentException("pageserver pgHost is required");
        }
        if (pgPort <= 0) {
            throw new IllegalArgumentException("pageserver pgPort must be positive");
        }
    }

    public String pgConnstring() {
        return "postgresql://" + normalizedPgHost() + ":" + pgPort;
    }

    public String normalizedPgHost() {
        if (pgHost.contains(".") || isIpv4(pgHost)) {
            return pgHost;
        }
        return pgHost + ".lakeon.svc.cluster.local";
    }

    private boolean isIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}
