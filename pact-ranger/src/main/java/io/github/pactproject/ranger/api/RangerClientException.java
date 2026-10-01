package io.github.pactproject.ranger.api;

public final class RangerClientException extends Exception {
    private final int statusCode;

    public RangerClientException(String message) {
        super(message);
        this.statusCode = -1;
    }

    public RangerClientException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public RangerClientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
