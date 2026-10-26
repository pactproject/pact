package io.github.pactproject.api.exception;

/** A declaration requests a feature that this backend does not implement. */
public final class BackendFeatureNotImplementedException extends ValidationException {
    public BackendFeatureNotImplementedException(String backendId, String feature) {
        super("Feature '" + feature + "' is not implemented by backend '" + backendId + "'");
    }
}
