package com.lakeon.service;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.DatabaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComputePodReconcileServiceTest {

    private DatabaseRepository repo;
    private ComputePodManager pods;
    private ComputePodReconcileService svc;

    @BeforeEach
    void setUp() {
        repo = mock(DatabaseRepository.class);
        pods = mock(ComputePodManager.class);
        svc = new ComputePodReconcileService(repo, pods);
    }

    private DatabaseEntity db(String id, String podName, String host) {
        var e = new DatabaseEntity();
        e.setId(id);
        e.setStatus(DatabaseStatus.RUNNING);
        e.setComputePodName(podName);
        e.setComputeHost(host);
        e.setComputePort(55433);
        return e;
    }

    @Test
    void no_drift_no_save() {
        var e = db("db_1", "pod-1", "10.0.0.1");
        when(repo.findAllByStatus(DatabaseStatus.RUNNING)).thenReturn(List.of(e));
        when(pods.getPodIp("pod-1")).thenReturn("10.0.0.1");

        var r = svc.reconcile(false);
        assertEquals(1, r.scanned());
        assertEquals(0, r.drifted());
        assertEquals(0, r.markedSuspended());
        verify(repo, never()).save(any());
    }

    @Test
    void drift_is_repaired() {
        var e = db("db_2", "pod-2", "10.0.0.5");  // stale
        when(repo.findAllByStatus(DatabaseStatus.RUNNING)).thenReturn(List.of(e));
        when(pods.getPodIp("pod-2")).thenReturn("10.0.0.2");  // actual

        var r = svc.reconcile(false);
        assertEquals(1, r.drifted());
        assertEquals("10.0.0.2", e.getComputeHost());
        assertTrue(r.driftedIds().contains("db_2"));
        verify(repo, times(1)).save(e);
    }

    @Test
    void missing_pod_marks_suspended() {
        var e = db("db_3", "pod-3", "10.0.0.3");
        when(repo.findAllByStatus(DatabaseStatus.RUNNING)).thenReturn(List.of(e));
        when(pods.getPodIp("pod-3")).thenReturn(null);

        var r = svc.reconcile(false);
        assertEquals(1, r.markedSuspended());
        assertEquals(DatabaseStatus.SUSPENDED, e.getStatus());
        assertNull(e.getComputeHost());
        assertNull(e.getComputePodName());
        assertTrue(r.suspendedIds().contains("db_3"));
        verify(repo, times(1)).save(e);
    }

    @Test
    void skips_rows_without_pod_name() {
        var e = db("db_4", null, null);
        when(repo.findAllByStatus(DatabaseStatus.RUNNING)).thenReturn(List.of(e));

        var r = svc.reconcile(false);
        assertEquals(1, r.scanned());
        assertEquals(0, r.drifted());
        assertEquals(0, r.markedSuspended());
        verify(repo, never()).save(any());
        verify(pods, never()).getPodIp(any());
    }

    @Test
    void multiple_mixed() {
        var ok = db("db_ok", "pod-ok", "10.0.0.1");
        var drift = db("db_drift", "pod-drift", "10.0.0.99");
        var gone = db("db_gone", "pod-gone", "10.0.0.50");
        when(repo.findAllByStatus(DatabaseStatus.RUNNING)).thenReturn(List.of(ok, drift, gone));
        when(pods.getPodIp("pod-ok")).thenReturn("10.0.0.1");
        when(pods.getPodIp("pod-drift")).thenReturn("10.0.0.2");
        when(pods.getPodIp("pod-gone")).thenReturn(null);

        var r = svc.reconcile(false);
        assertEquals(3, r.scanned());
        assertEquals(1, r.drifted());
        assertEquals(1, r.markedSuspended());
        verify(repo, times(2)).save(any());
    }
}
