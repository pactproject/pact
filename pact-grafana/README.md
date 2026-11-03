# PACT Grafana backend

`pact-grafana` manages existing users' direct folder/dashboard permissions in
one Grafana organization. Users must first sign in through OIDC so their
Grafana account and organization membership exist. PACT does not create or
delete users, change passwords, link authentication providers, change
organization roles, manage teams/membership, or create folders/dashboards.
Explicit identities fail with the shared checked unsupported-feature error.

## Access contract

Targets preserve Grafana's folder/dashboard hierarchy. This plugin uses a
flat `permissions` set because the target selects one ACL; it does not use
PostgreSQL's level-keyed permission map.

| Resource | Target fields | Permissions |
| --- | --- | --- |
| Folder | `folder` or `folderUid` | `View`, `Edit`, `Admin` |
| Dashboard by title | `folder` or `folderUid`, plus `dashboard` | `View`, `Edit`, `Admin` |
| Dashboard by UID | `dashboardUid`, optionally a folder selector | `View`, `Edit`, `Admin` |

The principal is the **exact Grafana login**, not a display name or an OIDC
subject unless your login mapping makes it one. Missing or ambiguous users
fail before writes with a reminder to sign in first. Resources must exist.

Native permission levels are `View` (1), `Edit` (2), and `Admin` (4), accepted
case-insensitively. There are no deny rules, wildcards, custom RBAC or privilege
catalog endpoints for these three fixed OSS ACL levels. Overlapping accesses
for the same canonical user/object aggregate to the highest permission.

Use `folder` (an exact full path from the organization root) or `folderUid`.
Use `dashboard` (an exact title with a required folder selector) or
`dashboardUid`. A dashboard UID may optionally have a folder selector, which
must match its actual parent. `folder: /` selects root dashboards but cannot
be used alone because the virtual root has no folder ACL. Empty path segments
are rejected; for folder titles containing `/`, use `folderUid`.
Name and UID aliases for the same selector are mutually exclusive. Titles
may contain spaces and `/`; dashboard titles are not split into path segments.
Missing/ambiguous references fail; no fuzzy result or first match is adopted.
Names and equivalent UIDs normalize to identical canonical typed resources
within the current catalog. Named selectors are resolved again on subsequent
reconciliations, as described in the ownership boundary below.

## Access examples

Kubernetes uses the generic arbitrary-backend-ID path:

```yaml
apiVersion: pactproject.github.io/v1
kind: DataAccess
metadata:
  name: grafana-readers
  namespace: operations
spec:
  resources:
    - monitoring:
        folder: Kubernetes
      access:
        - users: [alice]
          permissions: [View]
    - monitoring:
        folder: Kubernetes/Production
        dashboard: Cluster Overview
      access:
        - users: [alice]
          permissions: [Edit]
    - monitoring:
        dashboardUid: cluster-overview
      access:
        - users: [bob]
          permissions: [View]
```

The filesystem declaration uses the same string target fields and typed attributes:

```yaml
accesses:
  - principal: alice
    resource:
      backendId: monitoring
      target:
        folder: Kubernetes/Production
        dashboard: Cluster Overview
    attributes:
      permissions: [View]
```

## Configuration

```yaml
backends:
  - id: monitoring
    type: grafana
    config:
      endpoint: https://grafana.example
      token: ${GRAFANA_TOKEN}
      org-id: "1"
      reconciliation-mode: authoritative
      timeout-seconds: "30"
      page-size: "1000"
```

Use an organization Admin service-account token for complete organization-user,
folder/dashboard and ACL reads and ACL writes. The token must belong to the
configured positive `org-id`; PACT verifies `/api/org` before reconciliation.
An organization-scoped service-account token does not require server-admin
Basic credentials. Keep token privileges independent of reconciled ACLs.
Unknown keys and unsupported modes fail initialization. HTTPS uses JVM trust
and hostname verification; this version has no backend-specific CA option.
A reverse-proxy base path is supported. Redirects are not followed.

## Grant ownership contract

The only reconciliation mode is `authoritative` (the default). The scope is
the union of objects referenced by previous and desired declarations, plus
objects retained from uncertain transitions after failed compensation. For
each such object PACT replaces its entire direct ACL: undeclared user, team
and Viewer/Editor role entries are removed, including manually added entries.
Objects outside that scope are not changed. There is no preserve-unmanaged
mode. Use one writer for overlapping scopes; Grafana's ACL replacement has
no conditional revision check.

Removing the final declaration clears that object's direct ACL; accounts,
folders, dashboards and organization roles remain. Folder permissions are
inherited by descendants. Clearing a dashboard's direct ACL does not remove
access inherited from its parent, team membership, or administrator status.
Managing a folder's ACL therefore affects effective descendant access without
putting the descendants' own direct ACLs in write scope. This plugin cannot
restrict Prometheus queries or provide data-source/metric tenant isolation.

## Provisioning the permission-manager service account

Create a service account in the organization PACT will reconcile, assign its
organization role `Admin`, and generate a service-account token. Inject that
token through the deployment's Secret mechanism into the backend `token`
setting. PACT does not create its own connection identity or token.

The account needs complete organization-user and folder/dashboard catalogs,
folder details, all in-scope direct ACLs, and permission to replace those ACLs.
An organization Admin service account supplies this in Grafana OSS. An account
with access to only some dashboards can return an incomplete search catalog
and is unsuitable even if an individual permission write succeeds. Do not
configure hidden-user filtering for an otherwise authoritative scope: Grafana
may hide/preserve such ACL entries in its API independently of PACT.

The token is organization-scoped. Set `org-id` to that organization ID; PACT
checks the authenticated organization rather than silently switching it.
Use a separate backend ID and token for another organization or instance.
Keep emergency/server administrators outside declaration-driven access, and
use one writer per overlapping ACL scope.

## Reconciliation and ownership boundary

Preparation compiles the complete declaration, resolves references and reads
every in-scope ACL without mutations. Application revalidates desired bindings
and reads every in-scope ACL before its first write. Changing a user/object
binding between preparation and application fails closed. Plans and canonical
cleanup scopes are isolated per transaction; there is no persistent resolver
cache. Fresh catalog reads expose new objects and renames.

Compensation restores the previous agreed declaration over the union of old,
attempted and recovery scopes. It does not restore arbitrary manual ACL drift
from a pre-call snapshot. Partial writes and lost success responses are checked
backend failures; core rolls back the failing transaction too. Writes across
objects/services are not atomic. Deleted objects already have no ACL to clean;
missing desired objects still fail. No identity side effects exist.

Names select the object currently at that exact folder path/dashboard title
on each reconciliation. There is no persistent name-to-UID binding. Renaming
or moving an object outside the selector takes it out of that named scope:
its existing direct rights are left as they are. If a different object takes
the old name, it becomes the current target, including authoritative cleanup
when the previous declaration is removed. For example, after `Reports` is
renamed to `Archive` and another `Reports` is created, removing `folder: Reports`
clears the new `Reports` ACL and leaves `Archive` alone. The same rule applies
after restoring successful declarations from Kubernetes status.

A missing previous selector contributes no cleanup scope and does not block
removal or finalizer cleanup. Missing desired objects and ambiguous names
still fail before writes. A dashboard with an optional folder selector leaves
that combined scope when moved outside the selected parent. Use a UID without
a folder constraint when the declaration must follow that dashboard on moves;
folder UIDs similarly survive folder renames. Changing a UID is object
replacement; do not reuse UIDs for other objects. UID bindings are captured
only within one prepared transaction to detect preparation/application races.

Filesystem previous state is process-local. Kubernetes restores successful
declarations from status. Failed-compensation recovery scopes remain only in
core memory and are lost at restart. Initially empty history does not establish
that external ACLs are empty. Core reconciles participating backends once at
startup; afterward it skips ordinary unchanged declarations. A rename alone
does not trigger a backend reconciliation; there is no unconditional external-
drift repair.

## Identity reconciliation

Unlike the PostgreSQL backend, this plugin does not implement identity
reconciliation. Non-empty `spec.identities` is rejected with checked
`BackendFeatureNotImplementedException`, including `ensure: false`; use access
principals for users who already exist in the configured organization.

OIDC owns account registration and authentication. A missing user must first
sign in to Grafana, after which the declaration can be retried. A user who was
removed from Grafana contributes no restorable grant to previous state;
cleanup still removes other in-scope ACLs. Removing an access never deletes
an account. Passwords and OIDC associations are never read or changed.
Organization roles remain controlled by your Grafana/OIDC setup. Granting
folder/dashboard rights does not lower an organization administrator's access.

## API compatibility and operations

The adapter targets the documented legacy HTTP API available in Grafana
13.3.0: `/api/org`, `/api/org/users`, `/api/search`, `/api/folders/:uid`,
`/api/folders/:uid/permissions`, and `/api/dashboards/uid/:uid/permissions`.
These APIs are deprecated in Grafana 13 but remain available; there is no
write-schema fallback. Compatibility with other versions requires testing.
The test execution record is reported separately; API documentation is not
evidence of live authorization enforcement.

The transport uses JDK 21 `HttpClient` and the distribution's existing Jackson
version, adding no new runtime dependencies. The official
[Foundation SDK](https://github.com/grafana/grafana-foundation-sdk) builds
resource definitions; it is not an ACL HTTP client. The published
[AppNexus Java client API](https://appnexus.github.io/grafana-api-java-client/com/appnexus/grafana/client/GrafanaClient.html)
does not expose folder/dashboard ACL methods. Grafana's official
[OpenAPI HTTP client](https://github.com/grafana/grafana-openapi-client-go)
is for Go. A generated Java OpenAPI client remains an option if this narrow
HTTP surface grows.

Search catalogs are paginated using `limit` and `page`, including an empty
terminal page. Duplicate IDs, malformed rows, later-page failures, and a
non-empty page after a short page fail closed. The organization-user and ACL
APIs are unpaged; malformed/duplicate user IDs fail closed. Folder details
validate parent chains; missing parents and cycles are errors. No complete
snapshot or atomic catalog revision is provided by these APIs, so concurrent
catalog edits require a retry. HTTP connect/request timeouts are bounded by
`timeout-seconds` (1–300); there are no automatic read or write retries.
Never blindly retry ACL writes after a lost response: core compensates first.
`close()` releases the HTTP client without permission or identity mutations.

## Testing

Run `mvn -B -pl pact-grafana -am test` with JDK 21 and preview support. The
compiler, resolver, backend, HTTP and provider tests cover the common contract;
see [TESTING.md](../TESTING.md) for concrete references and limitations.
Live tests are opt-in and must use a disposable instance; see
[INTEGRATION_TESTS.md](../INTEGRATION_TESTS.md). Fixture-created local test
users stand in for already registered OIDC users and do not add user creation
to the backend. OIDC account linking itself is outside this plugin's scope.
