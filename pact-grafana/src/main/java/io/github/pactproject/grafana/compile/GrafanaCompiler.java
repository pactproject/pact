package io.github.pactproject.grafana.compile;

import io.github.pactproject.api.PactState;
import io.github.pactproject.api.exception.ValidationException;
import io.github.pactproject.api.value.SetValue;
import io.github.pactproject.api.value.StringValue;
import io.github.pactproject.grafana.model.GrafanaDeclaration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure declaration validation; no catalog or protocol dependencies. */
public final class GrafanaCompiler {
    private GrafanaCompiler() { }

    public static List<GrafanaDeclaration> compile(PactState state, String backendId) throws ValidationException {
        var result = new ArrayList<GrafanaDeclaration>();
        for (var access : state.accesses()) {
            if (!backendId.equals(access.resource().backendId())) throw invalid("backendId must match '" + backendId + "'");
            if (access.principal() == null || access.principal().isBlank()) throw invalid("principal must not be blank");
            var target = access.resource().target();
            if (target.isEmpty() || !Set.of("folder", "folderUid", "dashboard", "dashboardUid").containsAll(target.keySet())
                    || target.values().stream().anyMatch(value -> value == null || value.isBlank())) {
                throw invalid("target accepts non-blank folder, folderUid, dashboard or dashboardUid only");
            }
            if (target.containsKey("folder") && target.containsKey("folderUid")
                    || target.containsKey("dashboard") && target.containsKey("dashboardUid")) {
                throw invalid("name and UID aliases are mutually exclusive");
            }
            if (target.containsKey("dashboard") && !target.containsKey("folder") && !target.containsKey("folderUid")) {
                throw invalid("dashboard title requires folder or folderUid; use folder: '/' for the root");
            }
            String path = target.get("folder");
            if (path != null && !path.equals("/") && (path.startsWith("/") || path.endsWith("/") || path.contains("//"))) {
                throw invalid("folder must be a full relative path without empty segments, or '/'");
            }
            var reference = new GrafanaDeclaration.Target(path, target.get("folderUid"), target.get("dashboard"), target.get("dashboardUid"));
            if (!reference.isDashboard() && "/".equals(path)) throw invalid("the virtual root has no folder ACL");
            if (!access.attributes().keySet().equals(Set.of("permissions"))
                    || !(access.attributes().get("permissions") instanceof SetValue values) || values.values().isEmpty()) {
                throw invalid("permissions must be a non-empty set containing View, Edit or Admin");
            }
            int permission = 0;
            for (var value : values.values()) {
                if (!(value instanceof StringValue text) || text.value() == null) throw invalid("permissions must contain strings");
                int level = switch (text.value().toLowerCase(Locale.ROOT)) {
                    case "view" -> 1;
                    case "edit" -> 2;
                    case "admin" -> 4;
                    default -> throw invalid("unsupported permission: " + text.value());
                };
                permission = Math.max(permission, level);
            }
            result.add(new GrafanaDeclaration(access.principal(), reference, permission));
        }
        result.sort(Comparator.comparing(GrafanaDeclaration::principal)
                .thenComparing(value -> value.target().toString()).thenComparingInt(GrafanaDeclaration::permission));
        return List.copyOf(result);
    }

    private static ValidationException invalid(String message) { return new ValidationException("Grafana " + message); }
}
