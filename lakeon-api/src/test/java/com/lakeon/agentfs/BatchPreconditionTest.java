package com.lakeon.agentfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for Task 3: AgentFSController.batch must return per-op
 * {@code status:"precondition_failed"} entries instead of aborting the entire
 * batch when an individual op throws a precondition-style BadRequestException.
 *
 * Validation-style BadRequestExceptions (e.g. {@code unknown op}) must still
 * propagate and abort the batch.
 */
class BatchPreconditionTest {

    private AgentFSService svc;
    private AgentFSController controller;
    private TenantEntity tenant;
    private HttpServletRequest req;

    @BeforeEach
    void setup() {
        svc = mock(AgentFSService.class);
        controller = new AgentFSController(svc);
        tenant = new TenantEntity();
        tenant.setId("test-tenant");
        MockHttpServletRequest mockReq = new MockHttpServletRequest();
        mockReq.setAttribute("tenant", tenant);
        req = mockReq;
    }

    private static Map<String, Object> putOp(String path, String ifMatch) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "put");
        op.put("path", path);
        op.put("data_base64", Base64.getEncoder().encodeToString("hi".getBytes()));
        if (ifMatch != null) op.put("if_match", ifMatch);
        return op;
    }

    @Test
    void precondition_failed_on_one_op_does_not_abort_batch() {
        // First put → stale if-match → service throws precondition_failed
        when(svc.put(any(TenantEntity.class), eq("/m.txt"), any(byte[].class),
                any(), eq("stale-etag"), any()))
                .thenThrow(new BadRequestException("precondition_failed: if-match mismatch"));

        // Second put → success
        AgentFSService.FileRow ok = new AgentFSService.FileRow();
        ok.path = "/n.txt";
        ok.etag = "etag-n";
        when(svc.put(any(TenantEntity.class), eq("/n.txt"), any(byte[].class),
                any(), any(), any()))
                .thenReturn(ok);

        Map<String, Object> body = Map.of("ops", List.of(
                putOp("/m.txt", "stale-etag"),
                putOp("/n.txt", null)
        ));

        Map<String, Object> resp = controller.batch(req, body);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
        assertNotNull(results, "response must contain results list");
        assertEquals(2, results.size(), "both ops must produce a result");

        Map<String, Object> first = results.get(0);
        assertEquals("put", first.get("op"));
        assertEquals("/m.txt", first.get("path"));
        assertEquals("precondition_failed", first.get("status"));
        assertFalse(first.containsKey("etag"),
                "precondition_failed entries must NOT include an etag, got: " + first);

        Map<String, Object> second = results.get(1);
        assertEquals("put", second.get("op"));
        assertEquals("/n.txt", second.get("path"));
        assertEquals("ok", second.get("status"));
        assertEquals("etag-n", second.get("etag"));
    }

    @Test
    void unknown_op_still_aborts_batch() {
        // No svc stubbing needed — controller validates op kind before
        // dispatching. An "unknown op" must propagate the BadRequestException.
        Map<String, Object> badOp = new LinkedHashMap<>();
        badOp.put("op", "frobnicate");
        badOp.put("path", "/x.txt");

        Map<String, Object> body = Map.of("ops", List.of(badOp));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> controller.batch(req, body));
        assertTrue(ex.getMessage().contains("unknown op"),
                "validation BadRequestException must propagate, got: " + ex.getMessage());
    }

    @Test
    void append_precondition_failed_yields_per_op_status() {
        // Verify the new T1/T2 append path also demotes precondition_failed.
        when(svc.append(any(TenantEntity.class), eq("/a.txt"), any(byte[].class),
                eq("stale")))
                .thenThrow(new BadRequestException("precondition_failed: if-match mismatch"));

        Map<String, Object> appendOp = new LinkedHashMap<>();
        appendOp.put("op", "append");
        appendOp.put("path", "/a.txt");
        appendOp.put("data_base64", Base64.getEncoder().encodeToString("more".getBytes()));
        appendOp.put("if_match", "stale");

        Map<String, Object> body = Map.of("ops", List.of(appendOp));
        Map<String, Object> resp = controller.batch(req, body);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
        assertEquals(1, results.size());
        Map<String, Object> r = results.get(0);
        assertEquals("append", r.get("op"));
        assertEquals("/a.txt", r.get("path"));
        assertEquals("precondition_failed", r.get("status"));
        assertFalse(r.containsKey("etag"));
    }
}
