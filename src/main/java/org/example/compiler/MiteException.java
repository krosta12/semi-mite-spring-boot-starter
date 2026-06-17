package org.example.compiler;

public class MiteException extends RuntimeException {
    public MiteException(String message) {
        super(message);
    }

    public MiteException(String message, Throwable cause) {
        super(message, cause);
    }
}