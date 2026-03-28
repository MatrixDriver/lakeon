package com.lakeon.notebook;

import com.lakeon.config.LakeonProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class NotebookStorageService {

    private static final Logger log = LoggerFactory.getLogger(NotebookStorageService.class);
    private static final int MAX_VERSIONS = 50;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);

    private final LakeonProperties props;

    public NotebookStorageService(LakeonProperties props) {
        this.props = props;
    }

    // -------------------------------------------------------------------------
    // Internal helper
    // -------------------------------------------------------------------------

    private S3Client buildClient() {
        return S3Client.builder()
                .endpointOverride(URI.create(props.getObs().getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getObs().getAccessKey(), props.getObs().getSecretKey())))
                .region(Region.of("cn-north-4"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
                .build();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Read UTF-8 content from OBS. Returns null if the key does not exist.
     */
    public String read(String obsKey) {
        try (S3Client s3 = buildClient()) {
            GetObjectRequest req = GetObjectRequest.builder()
                    .bucket(props.getObs().getBucket())
                    .key(obsKey)
                    .build();
            return new String(s3.getObjectAsBytes(req).asByteArray(), StandardCharsets.UTF_8);
        } catch (NoSuchKeyException e) {
            return null;
        } catch (Exception e) {
            log.warn("Failed to read OBS key {}: {}", obsKey, e.getMessage());
            return null;
        }
    }

    /**
     * Write UTF-8 content to OBS with content-type application/json.
     */
    public void write(String obsKey, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (S3Client s3 = buildClient()) {
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(props.getObs().getBucket())
                    .key(obsKey)
                    .contentType("application/json; charset=utf-8")
                    .contentLength((long) bytes.length)
                    .build();
            s3.putObject(req, RequestBody.fromBytes(bytes));
        }
    }

    /**
     * Write a timestamped snapshot to the versions/ directory alongside the main file.
     * Prunes oldest versions if count exceeds MAX_VERSIONS.
     */
    public void createVersionSnapshot(String obsKey, String content) {
        // Derive versions dir: parent dir of obsKey + "versions/"
        int lastSlash = obsKey.lastIndexOf('/');
        String versionsDir = (lastSlash >= 0 ? obsKey.substring(0, lastSlash + 1) : "") + "versions/";
        String ts = TS_FMT.format(Instant.now());
        String versionKey = versionsDir + ts + ".json";

        write(versionKey, content);
        log.debug("Created version snapshot: {}", versionKey);

        // Prune if over limit
        try (S3Client s3 = buildClient()) {
            List<S3Object> versions = listVersionObjects(s3, versionsDir);
            if (versions.size() > MAX_VERSIONS) {
                // Sort ascending by key (timestamp-based names sort correctly)
                List<S3Object> sorted = versions.stream()
                        .sorted(Comparator.comparing(S3Object::key))
                        .collect(Collectors.toList());
                int toDelete = sorted.size() - MAX_VERSIONS;
                List<ObjectIdentifier> ids = sorted.subList(0, toDelete).stream()
                        .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                        .collect(Collectors.toList());
                s3.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(props.getObs().getBucket())
                        .delete(Delete.builder().objects(ids).build())
                        .build());
                log.debug("Pruned {} old notebook versions under {}", toDelete, versionsDir);
            }
        }
    }

    /**
     * List version timestamps (descending) for the given obsKey.
     */
    public List<String> listVersions(String obsKey) {
        int lastSlash = obsKey.lastIndexOf('/');
        String versionsDir = (lastSlash >= 0 ? obsKey.substring(0, lastSlash + 1) : "") + "versions/";
        try (S3Client s3 = buildClient()) {
            return listVersionObjects(s3, versionsDir).stream()
                    .map(S3Object::key)
                    .map(k -> k.substring(versionsDir.length()).replace(".json", ""))
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
        }
    }

    /**
     * Read a specific version by timestamp string.
     */
    public String readVersion(String obsKey, String timestamp) {
        int lastSlash = obsKey.lastIndexOf('/');
        String versionsDir = (lastSlash >= 0 ? obsKey.substring(0, lastSlash + 1) : "") + "versions/";
        return read(versionsDir + timestamp + ".json");
    }

    /**
     * Delete all OBS objects under the given prefix (the notebook directory).
     */
    public void deleteAll(String obsKeyPrefix) {
        try (S3Client s3 = buildClient()) {
            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder reqBuilder = ListObjectsV2Request.builder()
                        .bucket(props.getObs().getBucket())
                        .prefix(obsKeyPrefix)
                        .maxKeys(1000);
                if (continuationToken != null) {
                    reqBuilder.continuationToken(continuationToken);
                }
                ListObjectsV2Response resp = s3.listObjectsV2(reqBuilder.build());
                if (!resp.contents().isEmpty()) {
                    List<ObjectIdentifier> ids = resp.contents().stream()
                            .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                            .collect(Collectors.toList());
                    s3.deleteObjects(DeleteObjectsRequest.builder()
                            .bucket(props.getObs().getBucket())
                            .delete(Delete.builder().objects(ids).build())
                            .build());
                    log.debug("Deleted {} objects under prefix {}", ids.size(), obsKeyPrefix);
                }
                continuationToken = resp.isTruncated() ? resp.nextContinuationToken() : null;
            } while (continuationToken != null);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private List<S3Object> listVersionObjects(S3Client s3, String versionsDir) {
        List<S3Object> result = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request.Builder reqBuilder = ListObjectsV2Request.builder()
                    .bucket(props.getObs().getBucket())
                    .prefix(versionsDir)
                    .maxKeys(1000);
            if (continuationToken != null) {
                reqBuilder.continuationToken(continuationToken);
            }
            ListObjectsV2Response resp = s3.listObjectsV2(reqBuilder.build());
            result.addAll(resp.contents());
            continuationToken = resp.isTruncated() ? resp.nextContinuationToken() : null;
        } while (continuationToken != null);
        return result;
    }
}
