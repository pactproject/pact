package io.github.pactproject.api.exception;

public class BackendOperationException extends BackendException {
    public BackendOperationException(String message) {
        super(message);
    }

    public BackendOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
