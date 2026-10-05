package io.github.pactproject.elasticsearch.api;

public final class ElasticsearchClientException extends Exception {
    public ElasticsearchClientException(String message) {
        super(message);
    }

    public ElasticsearchClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
