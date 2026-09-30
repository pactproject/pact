package io.github.pactproject.core.exception;

public class RollbackException extends CoreException {
    public RollbackException(String message) {
        super(message);
    }

    public RollbackException(String message, Throwable cause) {
        super(message, cause);
    }

}
