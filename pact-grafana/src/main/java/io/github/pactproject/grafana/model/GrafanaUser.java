package io.github.pactproject.grafana.model;

public record GrafanaUser(long id, String login) {
    public GrafanaUser {
        if (id <= 0 || login == null || login.isBlank()) throw new IllegalArgumentException("Invalid Grafana user");
    }
}
