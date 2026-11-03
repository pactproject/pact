package io.github.pactproject.grafana.sync;

import io.github.pactproject.api.exception.ValidationException;
import io.github.pactproject.grafana.api.*;
import io.github.pactproject.grafana.model.*;
import java.util.*;

/** One fresh, complete, read-only catalog per preparation/application. */
public final class GrafanaResolver {
    private final Map<GrafanaResource, GrafanaObject> objects = new TreeMap<>();
    private final Map<String, List<GrafanaUser>> users = new TreeMap<>();
    private final Map<String, List<GrafanaResource>> folderPaths = new TreeMap<>();

    public GrafanaResolver(GrafanaClient client) throws GrafanaClientException, ValidationException {
        client.verifyOrganization();
        var userIds = new HashSet<Long>();
        for (var user : client.users()) {
            if (!userIds.add(user.id())) throw invalid("duplicate user ID: " + user.id());
            users.computeIfAbsent(user.login(), ignored -> new ArrayList<>()).add(user);
        }
        for (var object : client.objects()) {
            if (objects.putIfAbsent(object.resource(), object) != null) throw invalid("duplicate resource: " + object.resource());
        }
        for (var object : objects.values()) {
            if (!object.parentUid().isEmpty() && !objects.containsKey(folder(object.parentUid()))) {
                throw invalid("missing catalog parent '" + object.parentUid() + "' for " + object.resource());
            }
        }
        for (var object : objects.values()) {
            if (object.resource().kind() == GrafanaResource.Kind.FOLDER) {
                String path = path(object, new HashSet<>());
                folderPaths.computeIfAbsent(path, ignored -> new ArrayList<>()).add(object.resource());
            }
        }
    }

    public record Resolved(Map<GrafanaResource, List<GrafanaPermission>> grants) {
        public Resolved {
            var copy = new TreeMap<GrafanaResource, List<GrafanaPermission>>();
            grants.forEach((resource, entries) -> copy.put(resource, List.copyOf(entries)));
            grants = Collections.unmodifiableMap(copy);
        }
    }

    public Resolved resolve(List<GrafanaDeclaration> declarations, boolean previous) throws ValidationException {
        var grants = new TreeMap<GrafanaResource, Map<Long, Integer>>();
        for (var declaration : declarations) {
            var resource = resource(declaration.target(), previous);
            if (resource == null) continue; // A missing previous selector has no current in-scope object.
            var entries = grants.computeIfAbsent(resource, ignored -> new TreeMap<>());
            var matches = users.getOrDefault(declaration.principal(), List.of());
            if (matches.isEmpty() && previous) continue; // A removed account cannot retain a native user ACL.
            if (matches.size() != 1) throw invalid("user '" + declaration.principal() + "' is "
                    + (matches.isEmpty() ? "missing; the user must first sign in through OIDC in this organization" : "ambiguous"));
            entries.merge(matches.getFirst().id(), declaration.permission(), Math::max);
        }
        var result = new TreeMap<GrafanaResource, List<GrafanaPermission>>();
        grants.forEach((resource, entries) -> result.put(resource, entries.entrySet().stream()
                .map(entry -> GrafanaPermission.user(entry.getKey(), entry.getValue())).sorted().toList()));
        return new Resolved(result);
    }

    public Set<GrafanaResource> scope(List<GrafanaDeclaration> declarations) throws ValidationException {
        var result = new TreeSet<GrafanaResource>();
        for (var declaration : declarations) {
            var resource = resource(declaration.target(), true);
            if (resource != null) result.add(resource);
        }
        return Set.copyOf(result);
    }

    public boolean exists(GrafanaResource resource) { return objects.containsKey(resource); }

    private GrafanaResource resource(GrafanaDeclaration.Target target, boolean previous) throws ValidationException {
        if (previous && target.dashboardUid() != null && !objects.containsKey(dashboard(target.dashboardUid()))) return null;
        String parent = null;
        if (target.folderUid() != null) {
            parent = target.folderUid();
            if (!objects.containsKey(folder(parent))) {
                if (previous) return null;
                throw invalid("folder UID not found: " + parent);
            }
        } else if (target.folder() != null) {
            if (target.folder().equals("/")) parent = "";
            else {
                var matches = folderPaths.getOrDefault(target.folder(), List.of());
                if (previous && matches.isEmpty()) return null;
                if (matches.size() != 1) throw invalid("folder path '" + target.folder() + "' is "
                        + (matches.isEmpty() ? "missing" : "ambiguous"));
                parent = matches.getFirst().uid();
            }
        }
        if (!target.isDashboard()) return folder(parent);
        if (target.dashboardUid() != null) {
            var reference = dashboard(target.dashboardUid());
            var object = objects.get(reference);
            if (object == null) {
                if (previous) return null;
                throw invalid("dashboard UID not found: " + reference.uid());
            }
            if (parent != null && !object.parentUid().equals(parent)) {
                if (previous) return null;
                throw invalid("dashboard UID belongs to another folder: " + reference.uid());
            }
            return reference;
        }
        String expectedParent = parent;
        var matches = objects.values().stream().filter(object -> object.resource().kind() == GrafanaResource.Kind.DASHBOARD
                && object.title().equals(target.dashboard()) && object.parentUid().equals(expectedParent)).toList();
        if (previous && matches.isEmpty()) return null;
        if (matches.size() != 1) throw invalid("dashboard '" + target.dashboard() + "' in the selected folder is "
                + (matches.isEmpty() ? "missing" : "ambiguous"));
        return matches.getFirst().resource();
    }

    private String path(GrafanaObject object, Set<String> visiting) throws ValidationException {
        if (!visiting.add(object.resource().uid())) throw invalid("folder catalog contains a parent cycle");
        if (object.parentUid().isEmpty()) return object.title();
        return path(objects.get(folder(object.parentUid())), visiting) + "/" + object.title();
    }

    private static GrafanaResource folder(String uid) { return new GrafanaResource(GrafanaResource.Kind.FOLDER, uid); }
    private static GrafanaResource dashboard(String uid) { return new GrafanaResource(GrafanaResource.Kind.DASHBOARD, uid); }
    private static ValidationException invalid(String message) { return new ValidationException("Grafana " + message); }
}
