package com.lakeon.service.admin;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StuckTaskQueryServiceTest {

    private EntityManager em;
    private Query query;
    private StuckTaskQueryService service;

    @BeforeEach
    void setUp() {
        em = mock(EntityManager.class);
        query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        service = new StuckTaskQueryService(em);
    }

    @Test
    void emptyResultReturnsZeroCount() {
        when(query.getResultList()).thenReturn(List.of());
        Map<String, Object> result = service.run(10, null);
        assertThat(result.get("count")).isEqualTo(0);
    }

    @Test
    void hitFromOneTable() {
        when(query.getResultList())
                .thenReturn(List.of(new Object[]{
                        "task_42", "kb_abc", "WIKI_UPDATE", "in_progress",
                        java.sql.Timestamp.valueOf("2026-04-25 10:00:00"), 700
                }))
                .thenReturn(List.of())
                .thenReturn(List.of());
        Map<String, Object> result = service.run(5, null);
        assertThat(result.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) result.get("tasks");
        assertThat(tasks.get(0).get("task_type")).isEqualTo("WIKI_UPDATE");
        assertThat(tasks.get(0).get("source")).isEqualTo("wiki_run_logs");
    }

    @Test
    void filterByType() {
        when(query.getResultList())
                .thenReturn(List.of(new Object[]{
                        "task_a", "kb_a", "WIKI_UPDATE", "in_progress",
                        java.sql.Timestamp.valueOf("2026-04-25 10:00:00"), 700}))
                .thenReturn(Arrays.asList(new Object[]{
                        "task_b", null, "FUSE_BACKFILL", "in_progress",
                        java.sql.Timestamp.valueOf("2026-04-25 10:00:00"), 700}))
                .thenReturn(List.of());
        Map<String, Object> result = service.run(5, "WIKI_UPDATE");
        assertThat(result.get("count")).isEqualTo(1);
    }

    @Test
    void undefinedTableHandledGracefully() {
        when(query.getResultList())
                .thenReturn(List.of())   // wiki_run_logs
                .thenReturn(List.of())   // agentfs_jobs
                .thenThrow(new PersistenceException("relation does not exist"));  // kb_processing_tasks
        Map<String, Object> result = service.run(10, null);
        assertThat(result.get("count")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get("warnings");
        assertThat(warnings).isNotNull();
        assertThat(warnings.toString()).contains("kb_processing_tasks");
    }
}
