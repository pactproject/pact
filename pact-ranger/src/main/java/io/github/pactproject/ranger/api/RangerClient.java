package io.github.pactproject.ranger.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public interface RangerClient {
    void ensureUser(String username)
            throws RangerClientException;

    String getServiceType(String serviceName)
            throws RangerClientException;

    JsonNode getServiceDefinition(String serviceType)
            throws RangerClientException;

    List<JsonNode> getPolicies(String serviceName, int pageSize)
            throws RangerClientException;

    JsonNode createPolicy(ObjectNode policy)
            throws RangerClientException;

    JsonNode updatePolicy(long id, ObjectNode policy)
            throws RangerClientException;

    void deletePolicy(long id)
            throws RangerClientException;
}
