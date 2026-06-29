package com.lakeon.obs;

import com.lakeon.config.LakeonProperties;
import com.obs.services.ObsClient;
import com.obs.services.exception.ObsException;
import com.obs.services.model.ListObjectsRequest;
import com.obs.services.model.ObjectListing;
import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.ObsObject;
import com.obs.services.model.PutObjectRequest;
import com.obs.services.model.PutObjectResult;
import com.obs.services.model.S3Object;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin wrapper around the Huawei Cloud {@link ObsClient} SDK for direct object operations
 * used by {@code ManifestWriter} and the data-recovery toolkit.
 *
 * <p>The class deliberately exposes only the small surface the toolkit needs:
 * <ul>
 *   <li>{@link #putObject(String, String, String)} — write a JSON blob, optionally guarded by an
 *       {@code If-Match} ETag (optimistic concurrency for manifest updates).</li>
 *   <li>{@link #getObject(String)} — fetch the body + current ETag.</li>
 *   <li>{@link #exists(String)} — HEAD probe; 404 returns {@code false} instead of throwing.</li>
 *   <li>{@link #listPrefix(String)} — paginated list (handles {@code marker} continuation
 *       automatically).</li>
 *   <li>{@link #deleteKey(String)} — for test cleanup and retry-queue housekeeping.</li>
 * </ul>
 *
 * <p>Bucket is bound at construction time from {@code lakeon.obs.bucket}. The underlying
 * {@link ObsClient} bean is configured by {@link com.lakeon.config.ObsConfig}; when OBS is
 * not configured (local dev) the {@link ObsClient} dependency is absent and this component
 * is created with a {@code null} SDK handle — every method will then throw
 * {@link IllegalStateException}.
 */
@Component
public class LakeonObsClient {

    private static final Logger log = LoggerFactory.getLogger(LakeonObsClient.class);

    private final ObsClient obs;
    private final String bucket;

    @Autowired
    public LakeonObsClient(ObjectProvider<ObsClient> obsProvider, LakeonProperties props) {
        this(obsProvider.getIfAvailable(), props.getObs().getBucket());
    }

    /** Visible-for-testing constructor; allows passing a Mockito mock directly. */
    public LakeonObsClient(ObsClient obs, String bucket) {
        this.obs = obs;
        this.bucket = bucket;
    }

    public record ObsGetResult(String content, String etag) {}

    public record ObsListItem(String key, long size, String etag) {}

    /**
     * Write {@code content} as a UTF-8 JSON object at {@code key}.
     *
     * @param ifMatchETag optional ETag for optimistic concurrency; when non-null the request
     *                    sets the {@code If-Match} header so OBS will fail with HTTP 412
     *                    (surfaced as {@link ObsException}) if the live object has diverged.
     * @return the new object ETag returned by OBS (quote-stripped if necessary).
     */
    public String putObject(String key, String content, String ifMatchETag) {
        ensureClient();
        byte[] body = content.getBytes(StandardCharsets.UTF_8);

        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength((long) body.length);
        meta.setContentType("application/json");

        PutObjectRequest req = new PutObjectRequest();
        req.setBucketName(bucket);
        req.setObjectKey(key);
        req.setInput(new ByteArrayInputStream(body));
        req.setMetadata(meta);
        if (ifMatchETag != null && !ifMatchETag.isBlank()) {
            // x-obs-meta-* / addUserMetadata is for *user* metadata. For HTTP protocol
            // headers we use addUserHeaders, which the SDK forwards verbatim onto the
            // underlying HTTP request. OBS returns 412 Precondition Failed (surfaced as
            // ObsException) when the live ETag has changed.
            req.addUserHeaders("If-Match", stripQuotes(ifMatchETag));
        }

        PutObjectResult result = obs.putObject(req);
        String etag = result.getEtag();
        log.debug("LakeonObsClient.put: bucket={} key={} size={} etag={}", bucket, key, body.length, etag);
        return stripQuotes(etag);
    }

    public String putObjectBytes(String key, byte[] body, String contentType) {
        ensureClient();
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength((long) body.length);
        if (contentType != null && !contentType.isBlank()) {
            meta.setContentType(contentType);
        }

        PutObjectRequest req = new PutObjectRequest();
        req.setBucketName(bucket);
        req.setObjectKey(key);
        req.setInput(new ByteArrayInputStream(body));
        req.setMetadata(meta);

        PutObjectResult result = obs.putObject(req);
        String etag = result.getEtag();
        log.debug("LakeonObsClient.putBytes: bucket={} key={} size={} etag={}", bucket, key, body.length, etag);
        return stripQuotes(etag);
    }

    /**
     * Read the object as UTF-8 string. Caller is responsible for any JSON parsing.
     * Throws {@link ObsException} on 404 / network errors.
     */
    public ObsGetResult getObject(String key) {
        ensureClient();
        ObsObject obj = obs.getObject(bucket, key);
        try (InputStream in = obj.getObjectContent()) {
            byte[] bytes = in.readAllBytes();
            String etag = obj.getMetadata() != null ? obj.getMetadata().getEtag() : null;
            return new ObsGetResult(new String(bytes, StandardCharsets.UTF_8), stripQuotes(etag));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read OBS object " + bucket + "/" + key, e);
        }
    }

    /**
     * Returns {@code true} if the object exists, {@code false} on 404. Other OBS errors
     * (auth, network, 5xx) are re-thrown so callers can retry.
     */
    public boolean exists(String key) {
        ensureClient();
        try {
            ObjectMetadata md = obs.getObjectMetadata(bucket, key);
            return md != null;
        } catch (ObsException e) {
            if (e.getResponseCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * List all objects whose key starts with {@code prefix}, transparently following the
     * {@code marker} cursor across pages (OBS returns at most 1000 keys per response).
     */
    public List<ObsListItem> listPrefix(String prefix) {
        ensureClient();
        List<ObsListItem> out = new ArrayList<>();
        String marker = null;
        do {
            ListObjectsRequest req = new ListObjectsRequest(bucket);
            req.setPrefix(prefix);
            req.setMaxKeys(1000);
            if (marker != null) {
                req.setMarker(marker);
            }
            ObjectListing listing = obs.listObjects(req);
            for (ObsObject obj : listing.getObjects()) {
                ObjectMetadata md = obj.getMetadata();
                long size = md != null && md.getContentLength() != null ? md.getContentLength() : 0L;
                String etag = md != null ? stripQuotes(md.getEtag()) : null;
                out.add(new ObsListItem(obj.getObjectKey(), size, etag));
            }
            if (listing.isTruncated()) {
                marker = listing.getNextMarker();
                // OBS sometimes leaves nextMarker null; fall back to last key.
                if ((marker == null || marker.isEmpty()) && !listing.getObjects().isEmpty()) {
                    List<ObsObject> objs = listing.getObjects();
                    S3Object last = objs.get(objs.size() - 1);
                    marker = last.getObjectKey();
                }
            } else {
                marker = null;
            }
        } while (marker != null);
        log.debug("LakeonObsClient.list: bucket={} prefix={} count={}", bucket, prefix, out.size());
        return out;
    }

    /** Delete a single object. Used by tests and the retry-queue cleanup path. */
    public void deleteKey(String key) {
        ensureClient();
        obs.deleteObject(bucket, key);
        log.debug("LakeonObsClient.delete: bucket={} key={}", bucket, key);
    }

    /** The bucket this client is bound to (useful for log messages in callers). */
    public String bucket() {
        return bucket;
    }

    private void ensureClient() {
        if (obs == null) {
            throw new IllegalStateException(
                    "OBS client not configured (lakeon.obs.endpoint is blank); cannot perform OBS operations");
        }
    }

    /**
     * OBS returns ETag values wrapped in double quotes per the S3 / HTTP spec; strip them so
     * callers can compare against raw hex strings without surprises.
     */
    private static String stripQuotes(String etag) {
        if (etag == null) return null;
        if (etag.length() >= 2 && etag.startsWith("\"") && etag.endsWith("\"")) {
            return etag.substring(1, etag.length() - 1);
        }
        return etag;
    }
}
