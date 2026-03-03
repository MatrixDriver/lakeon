package com.lakeon.neon.exception;

public class NeonApiException extends RuntimeException {

    private final int statusCode;

    public NeonApiException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public NeonApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public NeonApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
