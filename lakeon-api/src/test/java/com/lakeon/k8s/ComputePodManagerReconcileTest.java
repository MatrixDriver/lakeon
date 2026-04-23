package com.lakeon.k8s;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.repository.DatabaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ComputePodManager#reconcileComputeHost(DatabaseEntity)}.
 * The method reconciles entity.computeHost against actual K8s pod IP,
 * updating the DB row when they drift.
 */
class ComputePodManagerReconcileTest {

    private ComputePodManager mgr;
    private DatabaseRepository repo;

    @BeforeEach
    void setUp() {
        KubernetesClient k8s = mock(KubernetesClient.class);
        LakeonProperties props = new LakeonProperties();
        LakeonProperties.K8sConfig k8sProps = new LakeonProperties.K8sConfig();
        k8sProps.setNamespace("lakeon-compute");
        props.setK8s(k8sProps);

        ObjectMapper om = new ObjectMapper();
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        repo = mock(DatabaseRepository.class);

        // Subclass to stub getPodIp — avoids full k8sClient wiring
        mgr = new ComputePodManager(k8s, props, om, reg, repo) {
            @Override
            public String getPodIp(String podName) {
                return switch (podName) {
                    case "compute-db-match" -> "10.0.0.1";
                    case "compute-db-drift" -> "10.0.0.2";   // actual differs from entity
                    case "compute-db-gone"  -> null;
                    default -> null;
                };
            }
        };
    }

    @Test
    void match_returns_true_and_does_not_save() {
        var entity = new DatabaseEntity();
        entity.setId("db_1");
        entity.setComputePodName("compute-db-match");
        entity.setComputeHost("10.0.0.1");

        assertTrue(mgr.reconcileComputeHost(entity));
        assertEquals("10.0.0.1", entity.getComputeHost());
        verify(repo, never()).save(any());
    }

    @Test
    void drift_updates_entity_and_saves() {
        var entity = new DatabaseEntity();
        entity.setId("db_2");
        entity.setComputePodName("compute-db-drift");
        entity.setComputeHost("10.0.0.99");  // stale

        assertTrue(mgr.reconcileComputeHost(entity));
        assertEquals("10.0.0.2", entity.getComputeHost());
        verify(repo, times(1)).save(entity);
    }

    @Test
    void pod_gone_returns_false_without_save() {
        var entity = new DatabaseEntity();
        entity.setId("db_3");
        entity.setComputePodName("compute-db-gone");
        entity.setComputeHost("10.0.0.50");

        assertFalse(mgr.reconcileComputeHost(entity));
        assertEquals("10.0.0.50", entity.getComputeHost());   // unchanged
        verify(repo, never()).save(any());
    }

    @Test
    void no_pod_name_returns_false() {
        var entity = new DatabaseEntity();
        entity.setId("db_4");
        entity.setComputePodName(null);

        assertFalse(mgr.reconcileComputeHost(entity));
        verify(repo, never()).save(any());
    }
}
