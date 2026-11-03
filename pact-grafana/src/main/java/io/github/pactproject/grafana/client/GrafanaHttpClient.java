package io.github.pactproject.grafana.client;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pactproject.api.DiagnosticSanitizer;
import io.github.pactproject.grafana.GrafanaConfig;
import io.github.pactproject.grafana.api.*;
import io.github.pactproject.grafana.model.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** HTTP/JSON boundary. No automatic retries, especially after indeterminate writes. */
public final class GrafanaHttpClient implements GrafanaClient {
    private final GrafanaConfig config;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private boolean closed;

    public GrafanaHttpClient(GrafanaConfig config) {
        this.config = Objects.requireNonNull(config);
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public void verifyOrganization() throws GrafanaClientException {
        var node = request("GET", "api/org", null);
        if (positiveLong(node, "id") != config.organizationId()) throw error("Authenticated Grafana organization differs from configured org-id");
    }

    @Override public List<GrafanaUser> users() throws GrafanaClientException {
        var result = new ArrayList<GrafanaUser>();
        var ids = new HashSet<Long>();
        for (var node : array(request("GET", "api/org/users", null))) {
            long id = positiveLong(node, "userId");
            if (!ids.add(id)) throw error("Duplicate Grafana organization user ID: " + id);
            if (positiveLong(node, "orgId") != config.organizationId()) throw error("Grafana user belongs to a different organization");
            result.add(new GrafanaUser(id, text(node, "login")));
        }
        return List.copyOf(result);
    }

    @Override public List<GrafanaObject> objects() throws GrafanaClientException {
        var result = new ArrayList<GrafanaObject>();
        for (var kind : GrafanaResource.Kind.values()) {
            String type = kind == GrafanaResource.Kind.FOLDER ? "dash-folder" : "dash-db";
            var seen = new HashSet<String>();
            boolean shortPage = false;
            for (int page = 1; ; page++) {
                if (page > 10000) throw error("Grafana search exceeded the catalog page limit");
                var rows = array(request("GET", "api/search?type=" + type + "&limit=" + config.pageSize() + "&page=" + page, null));
                if (rows.size() > config.pageSize() || shortPage && !rows.isEmpty()) throw error("Inconsistent Grafana search pagination");
                if (rows.isEmpty()) break;
                for (var node : rows) {
                    String uid = text(node, "uid"), title = text(node, "title");
                    if (!type.equals(text(node, "type"))) throw error("Grafana search returned an unexpected resource type");
                    if (!seen.add(uid)) throw error("Duplicate Grafana search UID: " + uid);
                    String parent;
                    if (kind == GrafanaResource.Kind.FOLDER) {
                        var detail = request("GET", "api/folders/" + encode(uid), null);
                        if (!uid.equals(text(detail, "uid")) || !title.equals(text(detail, "title"))) {
                            throw error("Grafana folder changed during catalog read: " + uid);
                        }
                        parent = optionalText(detail, "parentUid");
                    } else {
                        parent = optionalText(node, "folderUid");
                        if (parent.isEmpty() && optionalLong(node, "folderId") > 0) throw error("Grafana dashboard parent UID is missing: " + uid);
                    }
                    result.add(new GrafanaObject(new GrafanaResource(kind, uid), title, parent));
                }
                shortPage = rows.size() < config.pageSize();
            }
        }
        return List.copyOf(result);
    }

    @Override public List<GrafanaPermission> permissions(GrafanaResource resource) throws GrafanaClientException {
        var result = new ArrayList<GrafanaPermission>();
        for (var node : array(request("GET", permissionPath(resource), null))) {
            var inherited = node.get("inherited");
            if (inherited != null && !inherited.isBoolean()) throw error("Malformed Grafana ACL inherited flag");
            String uid = optionalText(node, "uid");
            if ((inherited == null || !inherited.booleanValue()) && !uid.isEmpty() && !uid.equals(resource.uid())) {
                throw error("Grafana direct ACL UID does not match the requested resource: " + uid);
            }
            long user = optionalLong(node, "userId"), team = optionalLong(node, "teamId");
            String role = optionalText(node, "role");
            if ((user > 0 ? 1 : 0) + (team > 0 ? 1 : 0) + (!role.isEmpty() ? 1 : 0) != 1) throw error("Malformed Grafana ACL subject");
            long level = positiveLong(node, "permission");
            if (level != 1 && level != 2 && level != 4) throw error("Unsupported Grafana ACL permission: " + level);
            try {
                var permission = new GrafanaPermission(user > 0 ? GrafanaPermission.Subject.USER : team > 0 ? GrafanaPermission.Subject.TEAM : GrafanaPermission.Subject.ROLE,
                        user > 0 ? Long.toString(user) : team > 0 ? Long.toString(team) : role, (int) level);
                // Validate every catalog row, but never replay inherited permissions as direct grants.
                if (inherited == null || !inherited.booleanValue()) result.add(permission);
            } catch (IllegalArgumentException e) { throw error(e.getMessage()); }
        }
        return List.copyOf(result);
    }

    @Override public void replacePermissions(GrafanaResource resource, List<GrafanaPermission> permissions) throws GrafanaClientException {
        var body = json.createObjectNode();
        var items = body.putArray("items");
        for (var permission : permissions) {
            var item = items.addObject().put("permission", permission.permission());
            switch (permission.subject()) {
                case USER -> item.put("userId", Long.parseLong(permission.identifier()));
                case TEAM -> item.put("teamId", Long.parseLong(permission.identifier()));
                case ROLE -> item.put("role", permission.identifier());
            }
        }
        var response = request("POST", permissionPath(resource), body.toString());
        text(response, "message");
    }

    private String permissionPath(GrafanaResource resource) {
        return (resource.kind() == GrafanaResource.Kind.FOLDER ? "api/folders/" : "api/dashboards/uid/") + encode(resource.uid()) + "/permissions";
    }

    private JsonNode request(String method, String path, String body) throws GrafanaClientException {
        if (closed) throw error("Grafana HTTP client is closed");
        try {
            String base = config.endpoint().toString();
            var builder = HttpRequest.newBuilder(URI.create((base.endsWith("/") ? base : base + "/") + path))
                    .timeout(config.timeout()).header("Accept", "application/json")
                    .header("Authorization", "Bearer " + config.token());
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw error("Grafana " + method + " " + path + " returned HTTP " + response.statusCode() + ": " + response.body());
            var node = json.readTree(response.body());
            if (node == null) throw error("Empty Grafana response: " + path);
            return node;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw error("Grafana " + method + " " + path + " interrupted");
        } catch (IOException | RuntimeException e) {
            // No raw cause: parser/transport messages can contain reflected credentials.
            throw error("Grafana " + method + " " + path + " failed: " + e.getMessage());
        }
    }

    private JsonNode array(JsonNode node) throws GrafanaClientException {
        if (!node.isArray()) throw error("Expected a Grafana catalog/ACL array: " + node);
        return node;
    }

    private String text(JsonNode node, String field) throws GrafanaClientException {
        var value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw error("Missing or malformed Grafana " + field + ": " + node);
        return value.textValue();
    }

    private String optionalText(JsonNode node, String field) throws GrafanaClientException {
        var value = node.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw error("Malformed Grafana " + field + ": " + node);
        return value.textValue();
    }

    private long positiveLong(JsonNode node, String field) throws GrafanaClientException {
        long value = optionalLong(node, field);
        if (value <= 0) throw error("Missing or invalid Grafana " + field + ": " + node);
        return value;
    }

    private long optionalLong(JsonNode node, String field) throws GrafanaClientException {
        var value = node.get(field);
        if (value == null || value.isNull()) return 0;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw error("Malformed Grafana " + field + ": " + node);
        return value.longValue();
    }

    private GrafanaClientException error(String message) {
        return new GrafanaClientException(DiagnosticSanitizer.redact(message, config.token(), "Bearer " + config.token()));
    }

    private static String encode(String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        return encoded.equals(".") || encoded.equals("..") ? encoded.replace(".", "%2E") : encoded;
    }

    @Override public void close() { closed = true; http.shutdownNow(); }
}
