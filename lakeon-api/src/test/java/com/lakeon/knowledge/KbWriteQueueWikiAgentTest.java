package com.lakeon.knowledge;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verifyNoInteractions;

class KbWriteQueueWikiAgentTest {

    @Test
    void executeWikiUpdateDispatchesToAgent() throws Exception {
        WikiAgentClient agent = mock(WikiAgentClient.class);
        when(agent.triggerIngest("t1", "kb1", "doc1")).thenReturn("task_x");

        KbWriteQueue queue = buildQueueWithMockAgent(agent);

        invokeExecuteWikiUpdate(queue, Map.of(
                "tenant_id", "t1", "kb_id", "kb1", "document_id", "doc1"));

        verify(agent).triggerIngest("t1", "kb1", "doc1");
    }

    @Test
    void executeWikiUpdateSwallowsAgentFailure() throws Exception {
        WikiAgentClient agent = mock(WikiAgentClient.class);
        when(agent.triggerIngest(any(), any(), any())).thenReturn(null);

        KbWriteQueue queue = buildQueueWithMockAgent(agent);

        // Should not throw even though agent returned null
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
            invokeExecuteWikiUpdate(queue, Map.of(
                "tenant_id", "t1", "kb_id", "kb1", "document_id", "doc1"))
        );

        verify(agent).triggerIngest("t1", "kb1", "doc1");
    }

    @Test
    void executeWikiUpdateThrowsOnMissingParams() throws Exception {
        WikiAgentClient agent = mock(WikiAgentClient.class);
        KbWriteQueue queue = buildQueueWithMockAgent(agent);

        // Missing document_id
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> invokeExecuteWikiUpdate(queue, Map.of("tenant_id", "t1", "kb_id", "kb1"))
        );
        assertTrue(ex.getMessage().contains("missing required params"));

        verifyNoInteractions(agent);
    }

    /**
     * Build a KbWriteQueue with mocked collaborators. Uses reflection to avoid
     * needing the full constructor signature in the test.
     */
    @SuppressWarnings("unchecked")
    private KbWriteQueue buildQueueWithMockAgent(WikiAgentClient agent) throws Exception {
        // Find the constructor and mock every param
        var ctors = KbWriteQueue.class.getDeclaredConstructors();
        var ctor = ctors[0]; // there's only one
        Class<?>[] paramTypes = ctor.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            if (paramTypes[i] == WikiAgentClient.class) {
                args[i] = agent;
            } else if (paramTypes[i].isPrimitive()) {
                args[i] = defaultPrimitive(paramTypes[i]);
            } else if (paramTypes[i].isInterface() || !java.lang.reflect.Modifier.isFinal(paramTypes[i].getModifiers())) {
                args[i] = Mockito.mock(paramTypes[i]);
            } else {
                args[i] = null;
            }
        }
        return (KbWriteQueue) ctor.newInstance(args);
    }

    private Object defaultPrimitive(Class<?> t) {
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == boolean.class) return false;
        if (t == double.class) return 0.0;
        return null;
    }

    private void invokeExecuteWikiUpdate(KbWriteQueue queue, Map<String, Object> params) throws Exception {
        Method m = KbWriteQueue.class.getDeclaredMethod("executeWikiUpdate", Map.class);
        m.setAccessible(true);
        try {
            m.invoke(queue, params);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Unwrap so tests see the real exception, not the reflection wrapper
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }
    }
}
