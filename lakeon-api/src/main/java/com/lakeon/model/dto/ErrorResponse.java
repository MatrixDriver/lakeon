package com.lakeon.model.dto;

public class ErrorResponse {
    private ErrorBody error;

    public ErrorResponse() {}

    public ErrorResponse(ErrorBody error) {
        this.error = error;
    }

    public ErrorBody getError() { return error; }
    public void setError(ErrorBody error) { this.error = error; }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new ErrorBody(code, message));
    }

    public static class ErrorBody {
        private String code;
        private String message;

        public ErrorBody() {}

        public ErrorBody(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
