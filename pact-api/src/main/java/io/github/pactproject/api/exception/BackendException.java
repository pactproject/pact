package io.github.pactproject.api.exception;

public class BackendException extends Exception {
    public BackendException(String message) {
        super(message);
    }

    public BackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
