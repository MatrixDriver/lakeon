package com.lakeon.obs;

import com.obs.services.ObsClient;
import com.obs.services.exception.ObsException;
import com.obs.services.model.ListObjectsRequest;
import com.obs.services.model.ObjectListing;
import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.ObsObject;
import com.obs.services.model.PutObjectRequest;
import com.obs.services.model.PutObjectResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeonObsClientTest {

    private static final String BUCKET = "lakeon-test-bucket";

    private ObsClient mockObs;
    private LakeonObsClient client;

    @BeforeEach
    void setUp() {
        mockObs = mock(ObsClient.class);
        client = new LakeonObsClient(mockObs, BUCKET);
    }

    @Test
    void putObject_noIfMatch_sendsBucketKeyAndContentTypeJson() {
        PutObjectResult result = mock(PutObjectResult.class);
        when(result.getEtag()).thenReturn("\"abc123\"");
        when(mockObs.putObject(any(PutObjectRequest.class))).thenReturn(result);

        String etag = client.putObject("manifests/foo.json", "{\"x\":1}", null);

        // Quote-stripping happens
        assertThat(etag).isEqualTo("abc123");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(mockObs).putObject(captor.capture());
        PutObjectRequest sent = captor.getValue();
        assertThat(sent.getBucketName()).isEqualTo(BUCKET);
        assertThat(sent.getObjectKey()).isEqualTo("manifests/foo.json");
        assertThat(sent.getMetadata().getContentType()).isEqualTo("application/json");
        assertThat(sent.getMetadata().getContentLength()).isEqualTo(7L);
        // If-Match header must NOT be set
        assertThat(sent.getUserHeaders()).doesNotContainKey("If-Match");
    }

    @Test
    void putObject_withIfMatch_setsIfMatchHeaderOnRequest() {
        PutObjectResult result = mock(PutObjectResult.class);
        when(result.getEtag()).thenReturn("newetag");
        when(mockObs.putObject(any(PutObjectRequest.class))).thenReturn(result);

        String etag = client.putObject("manifests/foo.json", "{\"x\":2}", "\"oldetag\"");

        assertThat(etag).isEqualTo("newetag");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(mockObs).putObject(captor.capture());
        PutObjectRequest sent = captor.getValue();
        // Quotes are stripped from the ETag passed to If-Match.
        assertThat(sent.getUserHeaders()).containsEntry("If-Match", "oldetag");
    }

    @Test
    void getObject_returnsContentAndStrippedEtag() {
        ObsObject obj = new ObsObject();
        obj.setObjectKey("manifests/foo.json");
        obj.setObjectContent(new ByteArrayInputStream("{\"hello\":\"world\"}".getBytes()));
        ObjectMetadata md = new ObjectMetadata();
        md.setEtag("\"deadbeef\"");
        obj.setMetadata(md);
        when(mockObs.getObject(BUCKET, "manifests/foo.json")).thenReturn(obj);

        LakeonObsClient.ObsGetResult res = client.getObject("manifests/foo.json");

        assertThat(res.content()).isEqualTo("{\"hello\":\"world\"}");
        assertThat(res.etag()).isEqualTo("deadbeef");
    }

    @Test
    void exists_returnsTrueWhenMetadataPresent() {
        when(mockObs.getObjectMetadata(BUCKET, "manifests/foo.json"))
                .thenReturn(new ObjectMetadata());

        assertThat(client.exists("manifests/foo.json")).isTrue();
    }

    @Test
    void exists_returnsFalseOn404() {
        ObsException notFound = new ObsException("not found");
        notFound.setResponseCode(404);
        when(mockObs.getObjectMetadata(BUCKET, "manifests/missing.json")).thenThrow(notFound);

        assertThat(client.exists("manifests/missing.json")).isFalse();
    }

    @Test
    void exists_propagatesNon404ObsExceptions() {
        ObsException serverErr = new ObsException("boom");
        serverErr.setResponseCode(500);
        when(mockObs.getObjectMetadata(BUCKET, "manifests/foo.json")).thenThrow(serverErr);

        assertThatThrownBy(() -> client.exists("manifests/foo.json"))
                .isInstanceOf(ObsException.class);
    }

    @Test
    void listPrefix_aggregatesAcrossPaginationViaMarker() {
        // Page 1: 2 objects, isTruncated=true, nextMarker="cursor-1"
        ObsObject a = obj("manifests/a.json", 10L, "\"e1\"");
        ObsObject b = obj("manifests/b.json", 20L, "\"e2\"");
        ObjectListing page1 = mock(ObjectListing.class);
        when(page1.getObjects()).thenReturn(List.of(a, b));
        when(page1.isTruncated()).thenReturn(true);
        when(page1.getNextMarker()).thenReturn("cursor-1");

        // Page 2: 1 object, isTruncated=false
        ObsObject c = obj("manifests/c.json", 30L, "\"e3\"");
        ObjectListing page2 = mock(ObjectListing.class);
        when(page2.getObjects()).thenReturn(List.of(c));
        when(page2.isTruncated()).thenReturn(false);

        // First call returns page1, second returns page2. We capture requests to verify the
        // second call's marker is "cursor-1".
        when(mockObs.listObjects(any(ListObjectsRequest.class))).thenReturn(page1, page2);

        List<LakeonObsClient.ObsListItem> items = client.listPrefix("manifests/");

        assertThat(items).hasSize(3);
        assertThat(items).extracting(LakeonObsClient.ObsListItem::key)
                .containsExactly("manifests/a.json", "manifests/b.json", "manifests/c.json");
        assertThat(items).extracting(LakeonObsClient.ObsListItem::size)
                .containsExactly(10L, 20L, 30L);
        assertThat(items).extracting(LakeonObsClient.ObsListItem::etag)
                .containsExactly("e1", "e2", "e3");

        ArgumentCaptor<ListObjectsRequest> captor = ArgumentCaptor.forClass(ListObjectsRequest.class);
        verify(mockObs, times(2)).listObjects(captor.capture());
        List<ListObjectsRequest> reqs = captor.getAllValues();
        assertThat(reqs.get(0).getMarker()).isNull();
        assertThat(reqs.get(0).getPrefix()).isEqualTo("manifests/");
        assertThat(reqs.get(0).getBucketName()).isEqualTo(BUCKET);
        assertThat(reqs.get(1).getMarker()).isEqualTo("cursor-1");
    }

    @Test
    void listPrefix_fallsBackToLastKeyWhenNextMarkerMissing() {
        // OBS can return isTruncated=true with a null/empty nextMarker; client must
        // fall back to the last key it saw.
        ObsObject a = obj("manifests/a.json", 1L, "\"e1\"");
        ObsObject b = obj("manifests/b.json", 2L, "\"e2\"");
        ObjectListing page1 = mock(ObjectListing.class);
        when(page1.getObjects()).thenReturn(List.of(a, b));
        when(page1.isTruncated()).thenReturn(true);
        when(page1.getNextMarker()).thenReturn(null);

        ObsObject c = obj("manifests/c.json", 3L, "\"e3\"");
        ObjectListing page2 = mock(ObjectListing.class);
        when(page2.getObjects()).thenReturn(List.of(c));
        when(page2.isTruncated()).thenReturn(false);

        when(mockObs.listObjects(any(ListObjectsRequest.class))).thenReturn(page1, page2);

        List<LakeonObsClient.ObsListItem> items = client.listPrefix("manifests/");
        assertThat(items).hasSize(3);

        ArgumentCaptor<ListObjectsRequest> captor = ArgumentCaptor.forClass(ListObjectsRequest.class);
        verify(mockObs, times(2)).listObjects(captor.capture());
        assertThat(captor.getAllValues().get(1).getMarker()).isEqualTo("manifests/b.json");
    }

    @Test
    void deleteKey_callsSdkDeleteWithBucketAndKey() {
        client.deleteKey("manifests/foo.json");
        verify(mockObs).deleteObject(eq(BUCKET), eq("manifests/foo.json"));
    }

    @Test
    void operationsFailFastWhenSdkClientIsNull() {
        LakeonObsClient noSdk = new LakeonObsClient((ObsClient) null, BUCKET);
        assertThatThrownBy(() -> noSdk.putObject("k", "{}", null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> noSdk.getObject("k"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> noSdk.exists("k"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> noSdk.listPrefix("p"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> noSdk.deleteKey("k"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ObsObject obj(String key, long size, String etag) {
        ObsObject o = new ObsObject();
        o.setObjectKey(key);
        ObjectMetadata md = new ObjectMetadata();
        md.setContentLength(size);
        md.setEtag(etag);
        o.setMetadata(md);
        return o;
    }
}
