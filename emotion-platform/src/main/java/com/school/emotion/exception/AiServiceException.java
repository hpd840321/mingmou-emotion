package com.school.emotion.exception;

public class AiServiceException extends RuntimeException {
    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
    public AiServiceException(String message) {
        super(message);
    }
}
