package com.lakeon.memory;

import com.lakeon.config.LakeonProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Proxy endpoint for embedding API — allows MCP clients to generate embeddings
 * using DBay's internal embedding service with their own API key for auth.
 */
@RestController
@RequestMapping("/api/v1")
public class EmbeddingController {

    private final LakeonProperties props;
    private final RestTemplate restTemplate = new RestTemplate();

    public EmbeddingController(LakeonProperties props) {
        this.props = props;
    }

    @PostMapping("/embedding")
    public Object embedding(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        // Auth is handled by the tenant filter — if we reach here, user is authenticated
        String url = props.getKnowledge().getEmbeddingApiUrl();
        String model = props.getKnowledge().getEmbeddingModel();
        String apiKey = props.getKnowledge().getEmbeddingApiKey();

        // Use the internal model if not specified in request
        if (!body.containsKey("model")) {
            body.put("model", model);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.set("Authorization", "Bearer " + apiKey);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Object> resp = restTemplate.exchange(url, HttpMethod.POST, entity, Object.class);
        return resp.getBody();
    }
}
