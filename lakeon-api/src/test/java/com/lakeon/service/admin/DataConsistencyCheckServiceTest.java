package com.lakeon.service.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

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
                "db_ready_implies_pod_running");
        assertThat(rules).doesNotContain("schema_seeded");
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
        assertThat(result.get("severity")).isEqualTo("OK");
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
                new Object[]{"write_42", "kb_abc", "RUNNING",
                        java.sql.Timestamp.valueOf("2026-04-25 10:00:00"), 600}
        ));
        Map<String, Object> result = service.run("enqueued_implies_drained", 5);
        verify(query).setParameter("threshold_minutes", 5);
        assertThat(result.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) result.get("violations");
        assertThat(violations.get(0).get("status")).isEqualTo("RUNNING");
    }

    // ── A: 7-min filter on db_ready_implies_pod_running ──────────────

    @Test
    void dbReadyRuleSqlExcludesYoungPods() {
        // Capture the SQL that would be issued for db_ready_implies_pod_running
        // and assert it filters by updated_at to skip young / cold-start rows.
        when(query.getResultList()).thenReturn(List.of());
        service.run("db_ready_implies_pod_running", 10);

        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
        verify(em).createNativeQuery(sqlCap.capture());
        String sql = sqlCap.getValue();
        assertThat(sql).contains("status = 'RUNNING'");
        assertThat(sql).contains("compute_host IS NULL");
        assertThat(sql).contains("updated_at < NOW() - INTERVAL '7 minutes'");
        assertThat(sql).contains("EXTRACT(EPOCH FROM (NOW() - updated_at))");
    }

    @Test
    void dbReadyRuleReportsAgeAndSelfHealable() {
        // empty string for compute_host instead of null — List.of() rejects null
        // entries when varargs unwraps a single Object[]; SQL filter treats
        // '' identically to NULL.
        when(query.getResultList()).thenReturn(List.of(
                new Object[]{"db_x", "mem_x", "t_x", "RUNNING", "", 900}
        ));
        Map<String, Object> result = service.run("db_ready_implies_pod_running", 10);
        assertThat(result.get("count")).isEqualTo(1);
        assertThat(result.get("self_healable")).isEqualTo(true);
        assertThat(result.get("max_age_seconds")).isEqualTo(900);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) result.get("violations");
        assertThat(violations.get(0).get("age_sec")).isEqualTo(900);
    }

    // ── B: severity classification ──────────────────────────────────

    @Test
    void severityInfoForSingleRecentViolation() {
        // count==1, age=900s (15min) → INFO
        assertThat(DataConsistencyCheckService.classifySeverity(1, OptionalInt.of(900)))
                .isEqualTo("INFO");
    }

    @Test
    void severityWarnForMultipleOrAgingViolations() {
        // count==2, age=600s → WARN
        assertThat(DataConsistencyCheckService.classifySeverity(2, OptionalInt.of(600)))
                .isEqualTo("WARN");
        // count==1, age=3600s (1h) → WARN (over INFO threshold of 30min)
        assertThat(DataConsistencyCheckService.classifySeverity(1, OptionalInt.of(3600)))
                .isEqualTo("WARN");
    }

    @Test
    void severityErrorForChronicOrWideBlast() {
        // count==5 → ERROR regardless of age
        assertThat(DataConsistencyCheckService.classifySeverity(5, OptionalInt.empty()))
                .isEqualTo("ERROR");
        // age > 2h → ERROR
        assertThat(DataConsistencyCheckService.classifySeverity(1, OptionalInt.of(7300)))
                .isEqualTo("ERROR");
    }

    @Test
    void severityFallsBackToWarnWhenAgeUnknown() {
        // No age data and count==1 → WARN (cannot prove it's transient)
        assertThat(DataConsistencyCheckService.classifySeverity(1, OptionalInt.empty()))
                .isEqualTo("WARN");
    }

    @Test
    void runReportsSeverityField() {
        when(query.getResultList()).thenReturn(List.of(
                new Object[]{"db_x", "mem_x", "t_x", "RUNNING", "", 600}  // 10min → INFO
        ));
        Map<String, Object> result = service.run("db_ready_implies_pod_running", 10);
        assertThat(result.get("severity")).isEqualTo("INFO");
    }
}
