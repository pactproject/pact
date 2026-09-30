package io.github.pactproject.core.exception;

public class BackendNotFoundException extends CoreException {
    public BackendNotFoundException(String backendId) {
        super("Backend not found: " + backendId);
    }
}
