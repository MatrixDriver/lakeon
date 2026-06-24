package com.lakeon.pageserver;

import com.lakeon.config.LakeonProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;

@Service
public class PageserverPlacementService {
    private static final int DEFAULT_PG_PORT = 6400;

    private final LakeonProperties props;

    public PageserverPlacementService(LakeonProperties props) {
        this.props = props;
    }

    public PageserverPlacement resolve(String tenantId, int shardId) {
        List<PageserverNode> nodes = configuredNodes();
        if (nodes.isEmpty()) {
            return new PageserverPlacement(tenantId, shardId, legacyDefaultNode(), 1L, "legacy-default");
        }
        int index = Math.floorMod(hashToInt(tenantId + ":" + shardId), nodes.size());
        return new PageserverPlacement(tenantId, shardId, nodes.get(index), 1L, "static-hash");
    }

    public PageserverNode defaultNode() {
        List<PageserverNode> nodes = configuredNodes();
        return nodes.isEmpty() ? legacyDefaultNode() : nodes.get(0);
    }

    public List<PageserverNode> configuredNodes() {
        return props.getNeon().getPageserverNodes().stream()
            .map(this::toNode)
            .sorted(Comparator.comparing(PageserverNode::id))
            .toList();
    }

    private PageserverNode toNode(LakeonProperties.PageserverNodeConfig config) {
        String httpUrl = requireNonBlank(config.getHttpUrl(), "pageserver node httpUrl is required");
        String id = config.getId() != null && !config.getId().isBlank()
            ? config.getId()
            : hostFromUrl(httpUrl);
        String pgHost = config.getPgHost() != null && !config.getPgHost().isBlank()
            ? config.getPgHost()
            : hostFromUrl(httpUrl);
        int pgPort = config.getPgPort() > 0 ? config.getPgPort() : DEFAULT_PG_PORT;
        return new PageserverNode(id, httpUrl, pgHost, pgPort);
    }

    private PageserverNode legacyDefaultNode() {
        String httpUrl = props.getNeon().getPageserverUrl();
        if (httpUrl == null || httpUrl.isBlank()) {
            httpUrl = "http://localhost:9898";
        }
        PageserverNode node = new PageserverNode("default", httpUrl, hostFromUrl(httpUrl), DEFAULT_PG_PORT);
        return new PageserverNode(node.id(), node.httpUrl(), node.normalizedPgHost(), node.pgPort());
    }

    private String hostFromUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() != null && !uri.getHost().isBlank()) {
                return uri.getHost();
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to legacy parser for short service names without a scheme.
        }
        return url.replaceAll("https?://", "").replaceAll(":\\d+$", "");
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private int hashToInt(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(bytes, 0, Integer.BYTES).getInt();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
