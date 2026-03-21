package com.lakeon.job;

/**
 * Listener notified when a Job reaches a terminal state (SUCCEEDED or FAILED).
 * Register beans implementing this interface to receive callbacks.
 */
public interface JobCompletionListener {
    void onJobCompleted(String jobId, boolean success, String result, String error);
}
