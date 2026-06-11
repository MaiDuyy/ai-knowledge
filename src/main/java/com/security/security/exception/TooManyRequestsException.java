package com.security.security.exception;

public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }

    public TooManyRequestsException() {
        super("Too many requests");
    }
}
