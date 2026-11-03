package io.github.pactproject.grafana.api;

import io.github.pactproject.grafana.model.*;
import java.util.List;

public interface GrafanaClient extends AutoCloseable {
    /** Verifies the authenticated organization before any catalog read or mutation. */
    void verifyOrganization() throws GrafanaClientException;
    List<GrafanaUser> users() throws GrafanaClientException;
    List<GrafanaObject> objects() throws GrafanaClientException;
    List<GrafanaPermission> permissions(GrafanaResource resource) throws GrafanaClientException;
    void replacePermissions(GrafanaResource resource, List<GrafanaPermission> permissions) throws GrafanaClientException;
    @Override default void close() throws GrafanaClientException { }
}
