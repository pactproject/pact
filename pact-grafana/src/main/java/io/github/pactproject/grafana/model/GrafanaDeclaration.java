package io.github.pactproject.grafana.model;

public record GrafanaDeclaration(String principal, Target target, int permission) {
    public record Target(String folder, String folderUid, String dashboard, String dashboardUid) {
        public boolean isDashboard() { return dashboard != null || dashboardUid != null; }
    }
}
