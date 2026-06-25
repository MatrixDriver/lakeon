package com.lakeon.k8s;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.pageserver.PageserverPlacementService;
import com.lakeon.repository.DatabaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

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
        mgr = new ComputePodManager(k8s, props, om, reg, new ComputeSpecBuilder(props, om), repo) {
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

    @Test
    void constructor_uses_supplied_compute_spec_builder() {
        KubernetesClient k8s = mock(KubernetesClient.class);
        LakeonProperties props = new LakeonProperties();
        ObjectMapper om = new ObjectMapper();
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        DatabaseRepository repository = mock(DatabaseRepository.class);
        ComputeSpecBuilder specBuilder = mock(ComputeSpecBuilder.class);

        ComputePodManager manager = new ComputePodManager(k8s, props, om, reg, specBuilder, repository);

        assertSame(specBuilder, manager.specBuilderForTests());
    }

    @Test
    void production_constructor_requires_pageserver_placement_service() {
        var publicConstructors = Arrays.stream(ComputePodManager.class.getConstructors())
            .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
            .toList();

        assertEquals(1, publicConstructors.size());
        assertTrue(Arrays.asList(publicConstructors.get(0).getParameterTypes())
            .contains(PageserverPlacementService.class));
    }

    @Test
    void updatePasswordInComputeConfig_updatesRoleRegardlessOfFieldOrder() throws Exception {
        String config = """
            {"spec":{"cluster":{"roles":[
              {"encrypted_password":"old","name":"user_a"},
              {"name":"user_b","encrypted_password":"keep"}
            ]}}}
            """;

        String updated = mgr.updatePasswordInComputeConfig(config, "user_a", "new-hash");

        var root = new ObjectMapper().readTree(updated);
        assertEquals("new-hash", root.path("spec").path("cluster").path("roles").get(0).path("encrypted_password").asText());
        assertEquals("keep", root.path("spec").path("cluster").path("roles").get(1).path("encrypted_password").asText());
    }
}
