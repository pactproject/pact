package io.github.pactproject.elasticsearch.api;

import com.fasterxml.jackson.databind.JsonNode;

public interface ElasticsearchClient {
    JsonNode getRole(String name) throws ElasticsearchClientException;

    JsonNode getUser(String username) throws ElasticsearchClientException;

    void putRole(String name, JsonNode definition)
            throws ElasticsearchClientException;

    void deleteRole(String name) throws ElasticsearchClientException;

    void putUser(String username, JsonNode definition)
            throws ElasticsearchClientException;
}
