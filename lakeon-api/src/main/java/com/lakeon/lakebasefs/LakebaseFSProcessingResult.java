package com.lakeon.lakebasefs;

public record LakebaseFSProcessingResult(boolean accepted, boolean retryable, String message) {
    public static LakebaseFSProcessingResult done(String message) {
        return new LakebaseFSProcessingResult(true, false, message);
    }

    public static LakebaseFSProcessingResult retry(String message) {
        return new LakebaseFSProcessingResult(false, true, message);
    }
}
