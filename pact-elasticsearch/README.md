# PACT Elasticsearch backend

`pact-elasticsearch` reconciles native users' index and cluster privileges and
existing role assignments. It uses the official Java API client 9.2.3 and is
tested against Elasticsearch 9.2.3. The existing index declaration format and
generated role names remain compatible.

For task-oriented DataAccess examples, account/password management, privilege
selection and revocation, see the [operator guide (Russian)](OPERATOR_GUIDE.ru.md).

## Backend configuration

```yaml
backends:
  - id: search
    type: elasticsearch
    config:
      endpoint: https://elasticsearch.example:9200
      username: pact
      password: ${ES_PASSWORD}
      principal-mode: ensure
      reconciliation-mode: authoritative
      ca-certificate: /etc/pact-ca/elasticsearch/ca.crt
```

Configure exactly one authentication method: `username` with `password`, or
`api-key` containing an encoded Elasticsearch API key. `endpoint` must be an
absolute HTTP(S) URL; a reverse proxy base path is supported. Unknown config
keys fail at initialization.

`principal-mode` defaults to `require`: users must already exist. `ensure`
permits creation only for users with a matching explicit `Identity` declaring
`ensure: true` (the default for explicit identities). `ensure: false` opts out
of creation. Kubernetes identities accept singular `backend` or a non-empty
`backends` list. Built-in users such as `elastic` and `kibana_system` cannot be
managed; built-in roles can be assigned.

`ca-certificate` is an optional PEM certificate bundle for this backend's HTTPS
client. When omitted, JVM system trust is used. When supplied, the bundle is
the client's trust roots; hostname verification remains enabled. Invalid or
unreadable bundles fail during initialization. It does not change global JVM
trust or affect other backend instances. See the [Helm CA example](../charts/pact/examples/elasticsearch-ca.yaml)
for mounting an existing ConfigMap; a Secret volume also works.

The connecting account needs Security API administration privileges to read
users, role definitions and the built-in privilege catalog, manage generated
roles and native-user assignments, and create/change passwords when requested
(`manage_security` covers these operations). Keep this account outside the
users PACT reconciles, especially in authoritative mode.

## Kubernetes declarations

Targets retain the usual `type: name` shape: `index: logs-*` or `cluster: "*"`.
The latter means the cluster behind this backend's endpoint; no other cluster
target value is accepted. Index privileges belong under `permissions.indices`,
cluster privileges under `permissions.cluster`. Names are checked against the
server's privilege catalog before writes.

```yaml
apiVersion: pactproject.github.io/v1
kind: DataAccess
metadata:
  name: log-reader
  namespace: operations
spec:
  identities:
    - backend: search
      name: alice
      ensure: true
  resources:
    - search:
        index: logs-*
      access:
        - users: [alice]
          permissions:
            indices: [read, view_index_metadata]
    - search:
        cluster: "*"
      access:
        - users: [alice]
          permissions:
            cluster: [monitor]
          roles: [monitoring_user, kibana_admin]
```

`roles` refers to existing built-in or custom roles by exact name and is only
accepted on `cluster: "*"`. It can appear without `permissions`, and can grant
application, `run_as`, DLS/FLS or other rights defined in those roles. PACT
validates each referenced role before writes but never edits/deletes its
definition. Generated Pact roles cannot be shared through `roles`; declare
their permissions instead. Elasticsearch's built-in roles may evolve with
the server. Its implicit default self-service user privileges also apply.

Direct privileges are unioned across declarations into one generated role per
backend ID and principal. The name is deterministic (`pact-` plus a hash), with
ownership metadata identifying PACT, the backend ID and principal. A collision
without matching ownership metadata fails instead of overwriting the role.
Roles-only declarations do not create an empty generated role. Direct
application privileges, DLS/FLS and deny rules are not declaration fields.

The filesystem provider uses the same target and attributes, for example:

```yaml
accesses:
  - principal: alice
    resource:
      backendId: search
      target: {cluster: "*"}
    attributes:
      permissions: {cluster: [monitor]}
      roles: [monitoring_user]
```

## Reconciliation scope

`reconciliation-mode` controls native-user role assignments:

| Mode | Behavior |
| --- | --- |
| `managed` (explicit compatibility mode) | Synchronize the generated role and roles explicitly named in previous/desired declarations. Preserve other assignments. Removing a previously declared role revokes that assignment. |
| `authoritative` (default) | Assign exactly the declared existing roles plus the generated role when there are direct privileges. Remove every other role assignment from users in previous/desired access or identity scope. |

Both modes leave role definitions shared with other users unchanged, preserve
user profile fields and enabled state, and leave users outside this scope
untouched. Authoritative mode can remove index, cluster, Kibana, application
and impersonation rights together when removing a mixed role assignment. An
identity-only user also participates in this mode. Removing the last
declaration removes in-scope assignments and the generated role; accounts are
never deleted. Overlapping declarations are unioned before reconciliation, so
removing one declaration retains grants still requested by another.

For migration from the previous managed default, explicitly set
`reconciliation-mode: managed` to retain foreign assignments. To use the new
default, first declare every role assignment needed by in-scope users,
including identity-only users. Shared role definitions remain unchanged;
undeclared memberships are removed. Keep the connecting administrator out of
this scope. Config changes alone do not force core to reapply equal restored
state; verify a new reconciliation against a complete declaration.

## Account passwords and compensation

Identity creation is independent of grants, so `spec.identities` alone can
ensure an account. Without `passwordSecretRef`, PACT generates a random
password only when creating the user. It sends the password to Elasticsearch
and never logs or persists it. Operators can later set a password manually;
subsequent reconciliation does not reset it.

An optional `passwordSecretRef: {name: credentials, key: password}` on a
Kubernetes identity uses that Secret for creation and rotates an existing
password when its source or resource version changes. Configure Secret read
RBAC separately as described by the Helm chart. Resolved password values are
removed from applied-state snapshots and status.

Preparation is read-only and validates the whole desired state before account,
role or assignment writes. Apply rechecks references and ownership before its
first write. Unchanged users/roles are not rewritten. Compensation restores
previous declared generated roles and assignments across the union of previous
and attempted user scopes, using the normal synchronizer in reverse. In
managed mode it preserves unrelated memberships; authoritative mode restores
exactly the previous declared role set, excluding manual drift. User profile
fields and enabled state are preserved. Passwords are not rotated back.
An unstarted transaction's rollback is a no-op. A request whose write succeeded but whose response was
lost is included in compensation.

New accounts survive failed applies. Password rotations run after grant
changes and cannot be undone, since Elasticsearch never reveals the old
password. Cross-backend compensation is best effort. Elasticsearch has no
conditional user role-assignment update: use one writer per scope and avoid
concurrent external changes to the same users/managed roles. An authoritative
backend must not overlap user scope with another writer.

## Testing

Recovery preparation retains native users and role assignments from failed
transitions, even when those users disappear from both the agreed and current
declarations. Authoritative/managed boundaries apply consistently during recovery
and rollback. Recovery context is held only in core memory; it is lost on process
restart. Incomplete built-in privilege catalogs fail before writes. See
[DIAGNOSTICS.md](../DIAGNOSTICS.md) for phase/action diagnostics and safe counts.

See the [shared test contract and coverage map](../TESTING.md) for required
backend scenarios and the limits of current evidence. Foreign backend IDs are
rejected; unchanged declarative state is skipped by core even after external drift.

```powershell
mvn -B -pl pact-elasticsearch -am test
```

Unit and local HTTP/HTTPS tests cover compilation, both modes, validation
before writes, idempotency, account creation/rotation, ownership races,
compensation, custom CA trust and hostname verification. Opt-in
`ElasticsearchLiveIntegrationTest` verifies real index and cluster enforcement,
shared/built-in assignments, scope boundaries, manual and Secret passwords,
compensation after a successful server write, and private CA trust. See the
[integration guide](../INTEGRATION_TESTS.md#focused-elasticsearch-923-with-tls)
for the disposable Elasticsearch 9.2.3 TLS stack.

The [filesystem application E2E](../INTEGRATION_TESTS.md#local-docker-compose-stack)
also exercises real plugin discovery, read-only grants, a declarative update to
allow writes, denial in another index, and empty-state revocation through the
same application instance. It checks generated-role removal before deleting its
unique fixture user/indices. This path runs in `.github/workflows/filesystem-e2e.yml`.
