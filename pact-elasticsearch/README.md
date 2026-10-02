# PACT Elasticsearch backend

`pact-elasticsearch` is being designed for self-managed Elasticsearch 9
deployments using the native security APIs available with the free license. The
first implementation will focus on native users and index-level role
privileges; paid authorization features and broader identity lifecycle
management are outside the initial scope.

This module is currently a design and Maven scaffold. Its backend behavior and
plugin registration have not been implemented yet.

## Initial access model

PACT will create one deterministic, PACT-owned Elasticsearch role per
principal. The role aggregates all index privileges for that principal across
the complete desired state for this Elasticsearch backend. An access entry
targets an Elasticsearch index name or pattern and declares Elasticsearch
index privileges:

```yaml
accesses:
  - principal: alice
    resource:
      backendId: search
      target:
        index: logs-*
    attributes:
      permissions:
        indices:
          - read
          - view_index_metadata
```

The `backendId` selects one configured Elasticsearch cluster. The `index`
target is an Elasticsearch index name or pattern. `permissions.indices` is a
set of Elasticsearch index privilege names validated against the backend's
supported contract. The compiler will merge entries for the same principal
and index pattern into that principal's role.

The role is assigned directly to the corresponding existing native-realm
Elasticsearch user. Role updates preserve roles not owned by PACT: the
backend reads the user's current role list, changes only its own role, and
writes the updated list. Elasticsearch does not provide a conditional update
for this read-modify-write sequence, so concurrent role changes can race.
Deployments should avoid other writers changing the same user's roles during
PACT reconciliation.

When a principal no longer has any desired access through this backend, PACT
will remove its role from the native user and delete that PACT-owned role. PACT
will not delete users or modify passwords for existing users. A user created
by ensure remains if later role or access reconciliation fails; this
side-effect is retryable and is not rolled back as part of access compensation.

## Principal existence

The backend will default to requiring every principal to exist as a native
Elasticsearch user. An explicit opt-in `principal-mode: ensure` will create a
missing native user with a cryptographically random password before applying
roles. The generated password is discarded and must never be logged or stored
by PACT. Elasticsearch native user creation requires a password; passwordless
provisioning is not part of this backend.

This is an access prerequisite, not a general identity lifecycle: PACT will
not rotate credentials, publish generated passwords, or delete native users.
Credentials used by PACT to authenticate to Elasticsearch are separate
backend configuration supplied by the deployment's secret mechanism.

## Initial scope and ownership

- Native Elasticsearch users only; external realm users and role mappings are
  not managed.
- Index privileges only. Cluster privileges and application privileges are
  not part of the initial contract.
- Document-level and field-level security (DLS/FLS) are excluded.
- PACT owns only roles bearing its ownership marker and only its own role on
  each native user. Unmanaged roles and users must remain untouched.
- A name collision with a role that is not PACT-owned must fail safely rather
  than overwrite or delete that role.
- Role names will be deterministic and PACT-prefixed so PACT can find its role
  for a principal across reconciliations. The exact encoding and ownership
  metadata are implementation details to settle alongside the compiler.
- Multiple PACT replicas reconciling the same cluster and users are not
  coordinated by this backend; use a single writer for a given managed scope.

Index role entries express additive privileges. This initial model has no
deny-permission semantics. Elasticsearch combines assigned roles, so other
roles assigned to a user can provide additional access beyond PACT's role.

## Planned module structure

```text
pact-elasticsearch/
  src/main/java/io/github/pactproject/elasticsearch/
    config/       endpoint, authentication, and principal-mode configuration
    client/       Elasticsearch Security API client and response models
    compile/      PactState to per-principal role definitions
    model/        immutable role and index-privilege models
    sync/         role/user diff and ownership-safe synchronization
    ElasticsearchBackend.java
    ElasticsearchBackendFactory.java
  src/main/resources/META-INF/services/
    io.github.pactproject.api.BackendFactory
```

The backend will use Elasticsearch Security APIs to inspect and reconcile
roles and native users. Configuration keys and the exact supported privilege
allow-list will be finalized with the first working client/compiler. The
backend service account will need sufficient permissions to read and manage
PACT-owned roles, inspect native users, and update their role assignments.

## Validation and integration testing

Unit tests should cover access compilation, stable role naming, aggregation of
multiple index patterns, preservation of unrelated user roles, ensure modes,
and ownership-safe deletion. Opt-in integration tests should target an
isolated Elasticsearch 9 cluster with the free license and dedicated test
users/roles. Never run reconciliation against a shared user or a role name
managed by another system.
