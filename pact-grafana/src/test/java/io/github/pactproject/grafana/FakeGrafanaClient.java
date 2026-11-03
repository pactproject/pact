package io.github.pactproject.grafana;

import io.github.pactproject.grafana.api.*;
import io.github.pactproject.grafana.model.*;
import java.util.*;

final class FakeGrafanaClient implements GrafanaClient {
    final List<GrafanaUser> users = new ArrayList<>(List.of(new GrafanaUser(10, "alice"), new GrafanaUser(20, "bob")));
    final List<GrafanaObject> objects = new ArrayList<>();
    final Map<GrafanaResource, List<GrafanaPermission>> acls = new HashMap<>();
    final Set<Integer> lostResponses = new HashSet<>();
    GrafanaResource failedRead;
    int writes, closes;
    boolean badOrganization;

    GrafanaResource add(GrafanaResource.Kind kind, String uid, String title, String parent) {
        var resource = new GrafanaResource(kind, uid);
        objects.add(new GrafanaObject(resource, title, parent));
        acls.put(resource, List.of());
        return resource;
    }
    @Override public void verifyOrganization() throws GrafanaClientException {
        if (badOrganization) throw new GrafanaClientException("Wrong organization");
    }
    @Override public List<GrafanaUser> users() { return List.copyOf(users); }
    @Override public List<GrafanaObject> objects() { return List.copyOf(objects); }
    @Override public List<GrafanaPermission> permissions(GrafanaResource resource) throws GrafanaClientException {
        if (resource.equals(failedRead)) throw new GrafanaClientException("ACL read failed");
        return acls.get(resource);
    }
    @Override public void replacePermissions(GrafanaResource resource, List<GrafanaPermission> permissions) throws GrafanaClientException {
        acls.put(resource, List.copyOf(permissions));
        if (lostResponses.contains(++writes)) throw new GrafanaClientException("Lost success response");
    }
    @Override public void close() { closes++; }
}
