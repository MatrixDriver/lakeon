package com.lakeon.service.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataConsistencyCheckServiceTest {

    private EntityManager em;
    private Query query;
    private DataConsistencyCheckService service;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        service = new DataConsistencyCheckService(em);
    }

    @Test
    void listsAvailableRules() {
        Map<String, Object> result = service.run("__list__", 10);
        assertThat(result.get("rules")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> rules = (List<String>) result.get("rules");
        assertThat(rules).contains("kb_implies_db_id", "enqueued_implies_drained",
                "db_ready_implies_pod_running", "schema_seeded");
    }

    @Test
    void unknownRuleReturnsErrorPayload() {
        Map<String, Object> result = service.run("bogus_rule", 10);
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("message").toString()).contains("unknown");
    }

    @Test
    void okWhenNoViolations() {
        when(query.getResultList()).thenReturn(List.of());
        Map<String, Object> result = service.run("kb_implies_db_id", 10);
        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("count")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) result.get("violations");
        assertThat(violations).isEmpty();
    }

    @Test
    void countAndShapeWhenViolationsPresent() {
        when(query.getResultList()).thenReturn(List.of(
                new Object[]{"kb_a", "demo", "t_xyz", null},
                new Object[]{"kb_b", "test", "t_xyz", null}
        ));
        Map<String, Object> result = service.run("kb_implies_db_id", 10);
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat(result.get("count")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) result.get("violations");
        assertThat(violations).hasSize(2);
        assertThat(violations.get(0).get("kb_id")).isEqualTo("kb_a");
        assertThat(violations.get(0).get("name")).isEqualTo("demo");
    }

    @Test
    void enqueuedImpliesDrainedUsesThresholdParam() {
        when(query.getResultList()).thenReturn(List.of(
                new Object[]{"write_42", "kb_abc", java.sql.Timestamp.valueOf("2026-04-25 10:00:00"), 600}
        ));
        Map<String, Object> result = service.run("enqueued_implies_drained", 5);
        verify(query).setParameter("threshold_minutes", 5);
        assertThat(result.get("count")).isEqualTo(1);
    }
}
