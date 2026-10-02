package io.github.pactproject.postgresql.api;

public class PostgreSqlClientException extends Exception {
    public PostgreSqlClientException(String message, Throwable cause) {
        super(message, cause);
    }

    public PostgreSqlClientException(String message) {
        super(message);
    }
}
