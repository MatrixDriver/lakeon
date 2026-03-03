package com.lakeon.service.exception;

public class WakeComputeTimeoutException extends RuntimeException {
    public WakeComputeTimeoutException(String message) {
        super(message);
    }
}
