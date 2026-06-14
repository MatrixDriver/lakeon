package com.lakeon.lakebasefs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/** HTTP client for memory-svc's /lbfs/derive endpoint. */
@Component
public class MemorySvcClient {

    private static final Logger log = LoggerFactory.getLogger(MemorySvcClient.class);

    private final RestClient client;

    public MemorySvcClient(
            @Value("${lakeon.memory.service-url:http://memory-svc:8001}")
            String baseUrl) {
        this.client = RestClient.builder()
            .baseUrl(baseUrl)
            .build();
    }

    /**
     * POST /lbfs/derive. The `body` map is the DeriveRequest payload;
     * `baseConnstr` becomes the x-database-connstr header.
     * Returns the HTTP status code (200 = ingested or noop, 202 = target
     * base still provisioning, 4xx/5xx = error).
     * Never throws on non-2xx — caller inspects the status code to decide
     * retry / ACK / poison. Network errors surface as status=0.
     */
    public DeriveResponse derive(String baseConnstr, Map<String, Object> body) {
        try {
            ResponseEntity<String> resp = client.post()
                .uri("/lbfs/derive")
                .header("x-database-connstr", baseConnstr)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    // Don't throw — let caller handle via status code
                })
                .toEntity(String.class);
            return new DeriveResponse(resp.getStatusCode().value(),
                                       resp.getBody() != null ? resp.getBody() : "");
        } catch (RestClientResponseException e) {
            return new DeriveResponse(e.getStatusCode().value(),
                                       e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            log.warn("memory-svc unreachable: {}", e.getMessage());
            return new DeriveResponse(0, "network_error: " + e.getMessage());
        }
    }

    public record DeriveResponse(int statusCode, String body) {
        public boolean isSuccess() { return statusCode >= 200 && statusCode < 300; }
        public boolean isAccepted() { return statusCode == 202; }
    }
}
