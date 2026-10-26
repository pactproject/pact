# pact-kubernetes

`pact-kubernetes` supplies the managed Kubernetes state provider for PACT. It
observes namespace-scoped `DataAccess` custom resources cluster-wide, compiles
their desired permissions, and invokes the PACT reconciler serially. Unlike
snapshot providers such as the filesystem provider, this provider keeps the
application running until shutdown.

## Configuration

Select `kubernetes` as the state provider type. The optional provider
configuration keys are `group`, `version`, and `plural`; their defaults are
`com.example.ru`, `v1`, and `dataaccesses`. They can also be set with
`DATA_ACCESS_GROUP`, `DATA_ACCESS_VERSION`, and `DATA_ACCESS_PLURAL`
environment variables. Explicit provider configuration takes precedence over
the environment.

Controller timing can be tuned with `informer-resync-millis` (default 5000),
`informer-start-timeout-seconds` (default 30), and
`status-retry-delays-millis` (default `100,200,400`). The retry setting is a
comma-separated sequence of positive millisecond delays; it controls retries
after the initial status patch attempt. These settings are configured in the
state provider's `config` map.

`KubernetesConfig` owns resource coordinate parsing and defaults. The
`controller` package contains informer, queue, status, and finalizer behavior;
the `compile` package translates DataAccess specs into PACT state.

The Fabric8 client uses its normal Kubernetes configuration, including the
in-cluster service account when running in Kubernetes. The configured resource
is expected to be namespace-scoped and to expose the `status` subresource.

Use the configured Artifact Keeper backend ID as the key in
`DataAccess.spec.resources`. A location with `name` identifies a repository;
`access.users` can mix human usernames and `svc-*` service accounts, with no
explicit principal type required. The backend accepts the generic compiler's
`name` / `permissions` representation as well as `registry`'s normalized
`repository` / `actions` form.
Use `{type: project, name: payments}` for a named target, or
`{type: artifact, repository: releases, path: org/app.jar}` for a qualified
artifact. `{type: <type>, id: <UUID>}` remains available for explicit IDs.
Registry access also accepts `principals` instead of `users`, an explicit
`principal-type` (`user`, `service_account`, `group`, or `anonymous`), and
`allowed_cidrs: [...]` directly in the access entry with the current API profile. Group
principals accept exact group names or UUIDs. See the [Artifact Keeper model and examples](../pact-artifact-keeper/README.md).
For a custom backend ID, use `users` for principal names/IDs; the `principals`
alias is supplied by the specialized `registry` compiler.

### Identities

`DataAccess.spec.identities` can declare PostgreSQL roles and Elasticsearch
native users separately from grants. A referenced password is read from a Kubernetes Secret in the same
namespace as the `DataAccess`:

```yaml
identities:
  - backend: analytics-db
    name: app
    ensure: true
    passwordSecretRef:
      name: app-credentials
      key: password
resources: []
```

`ensure` defaults to `true`; explicitly set `false` to require an existing
account. Elasticsearch creation also requires backend `principal-mode: ensure`.
`passwordSecretRef` is optional. Existing PostgreSQL
roles must have `LOGIN` when PACT is asked to set a password; PACT does not
change existing role attributes. PACT does not delete roles when an identity
declaration is removed. Each backend/principal identity must be declared by
only one `DataAccess` resource. During informer resync, PACT checks the
resource version of each referenced Secret; a changed version triggers
reconciliation without requiring a `DataAccess` generation change. The
default resync interval is five seconds, so rotation is applied on the next
resync. A missing or invalid Secret reports an error and is retried by a later
resync. Passwords are not written to `DataAccess` status or applied-state
snapshots.

Use `backends` instead of `backend` to ensure the same named identity across
several configured instances, each of which must support explicit identities:

```yaml
identities:
  - backends: [analytics-db, search]
    name: app
    passwordSecretRef: {name: app-credentials, key: password}
resources: []
```

Exactly one of `backend` or `backends` is required; the list must be non-empty.
Each backend/principal pair has independent ownership and persisted password
version. One Secret read supplies consistent data to identities sharing that
Secret within a resolution. Removing a backend from the list removes its
identity declaration without deleting its account. Ranger and Artifact Keeper
currently reject explicit identities with a checked not-implemented error;
their existing principal ensure behavior from access declarations is separate.

### Target lists

A target field can be a scalar or a non-empty list of scalar values. When
multiple fields are lists, the compiler expands them into the Cartesian
product of concrete targets before creating PACT resources. For example,
two schemas and three columns produce six target combinations for each user.
The same behavior applies to the Registry `name` field. Empty lists and
nested/non-scalar list items are rejected.

```yaml
resources:
  - postgresql:
      database: analytics
      schema: [sales, audit]
      table: orders
      column: [id, email]
    access:
      - users: [app]
        permissions:
          column: [SELECT]
```

## Controller behavior

- Startup lists all `DataAccess` resources and rebuilds applied state only from
  `status.lastAppliedSpec`; it then starts the informer and waits for its cache
  to sync before processing events.
- Add and changed-generation update events are processed in FIFO order. Updates
  that do not change `metadata.generation` are ignored. A deletion timestamp
  takes priority over generation checks; status-only updates produced while
  cleanup is in progress do not enqueue repeated cleanup passes.
- Each event computes the cluster-wide desired state from the last successful
  snapshot of every resource. An event only replaces its own
  `namespace/name` snapshot after the core reports successful reconciliation.
- Status is written through the status subresource. Failed validation or
  backend reconciliation sets `phase: Error` and `observedGeneration` while
  preserving the last successful `lastAppliedSpec`.
- The controller adds `<group>/data-access` before applying a resource.
  During deletion, it removes that finalizer only after successful cleanup.
  A repeated delete callback after the resource's applied snapshot has already
  been removed is treated as a no-op.
- Shutdown stops accepting events, lets queued reconciliation work finish,
  stops the informer, and closes the Kubernetes client.

Required cluster-wide RBAC:

```yaml
rules:
  - apiGroups: ["com.example.ru"]
    resources: ["dataaccesses"]
    verbs: ["get", "list", "watch", "patch"]
  - apiGroups: ["com.example.ru"]
    resources: ["dataaccesses/status"]
    verbs: ["patch"]
```

Replace the API group above when configuring a different group. The controller
does not create or modify the CRD. Secret reads are only needed when identities
use `passwordSecretRef`. The controller reads each referenced Secret with a
named `get`; it does not need `list` or `watch` permission on Secrets. Grant
access with a Role and RoleBinding alongside the SealedSecret, restricted by
`resourceNames` to that Secret. The binding subject is the controller's
ServiceAccount, even when it lives in a different namespace:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: pact-read-app-credentials
  namespace: payments
rules:
  - apiGroups: [""]
    resources: ["secrets"]
    resourceNames: ["app-credentials"]
    verbs: ["get"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: pact-read-app-credentials
  namespace: payments
subjects:
  - kind: ServiceAccount
    name: pact
    namespace: pact-system
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: pact-read-app-credentials
```

The `Role` and `RoleBinding` can be managed by ArgoCD in the same GitOps
application as the SealedSecret. The Role restricts access to the Secret
object, not to an individual data key; use one Secret per credential when
separate key-level access boundaries are required. Do not add cluster-wide
`get secrets` permission to the controller role.

## Current scope

Registry resources for Artifact Keeper are wired end to end. Ozone and Trino
resources require a configured Ranger backend with a matching backend id.
The in-repository Helm chart is in [`charts/pact`](../charts/pact); it can
install a generic `DataAccess` CRD and the controller's RBAC. Service-specific
CRD schema validation and external backend deployments remain operator
responsibilities.

## Artifact Keeper end-to-end verification

The opt-in `KubernetesArtifactKeeperLiveIntegrationTest` uses a real isolated
Kubernetes API and Artifact Keeper 1.11.1. It covers named subjects/objects,
mixed users and service accounts, arbitrary backend IDs, flat CIDRs, status
persistence, updates and finalizer cleanup. A second scenario starts two
separate controller JVMs and verifies restart restores applied state without
rewriting permissions. Run it using the `kubernetes` Compose profile described
in [INTEGRATION_TESTS.md](../INTEGRATION_TESTS.md).
