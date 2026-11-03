package io.github.pactproject.grafana.sync;

import io.github.pactproject.api.exception.ValidationException;
import io.github.pactproject.grafana.api.*;
import io.github.pactproject.grafana.model.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GrafanaSynchronizer {
    private static final Logger log = LoggerFactory.getLogger(GrafanaSynchronizer.class);
    private GrafanaSynchronizer() { }

    /** Reads the complete write scope before performing even the first replacement. */
    public static void synchronize(String backendId, GrafanaClient client, GrafanaResolver resolver,
            Set<GrafanaResource> scope, GrafanaResolver.Resolved desired, boolean write)
            throws GrafanaClientException, ValidationException {
        var changes = new TreeMap<GrafanaResource, List<GrafanaPermission>>();
        for (var resource : new TreeSet<>(scope)) {
            if (!resolver.exists(resource)) {
                if (desired.grants().containsKey(resource)) throw new ValidationException("Grafana desired resource disappeared: " + resource);
                continue;
            }
            var actual = client.permissions(resource).stream().sorted().toList();
            var wanted = desired.grants().getOrDefault(resource, List.of());
            if (!actual.equals(wanted)) changes.put(resource, wanted);
        }
        log.debug("backend={} phase=plan scope={} desired_objects={} planned_replace_acl={} write={}",
                backendId, new TreeSet<>(scope), desired.grants().size(), changes.size(), write);
        if (!write) return;
        int completed = 0;
        try {
            for (var change : changes.entrySet()) {
                log.debug("backend={} action=replace_acl result=started resource={} permissions={}", backendId, change.getKey(), change.getValue());
                client.replacePermissions(change.getKey(), change.getValue());
                completed++;
                log.debug("backend={} action=replace_acl result=completed resource={}", backendId, change.getKey());
            }
        } catch (GrafanaClientException e) {
            log.warn("backend={} action=replace_acl result=failed confirmed_completed={} planned={}", backendId, completed, changes.size());
            throw e;
        }
    }
}
