package io.github.pactproject.api.exception;

public class ValidationException extends BackendException {
    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
