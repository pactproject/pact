# PACT Artifact Keeper backend

`pact-artifact-keeper` currently reconciles user permissions on Artifact Keeper
repositories. Its configured backend instance is the authoritative source of
truth for the permissions returned by the Artifact Keeper `GET
/api/v1/permissions` endpoint: a permission absent from PACT's desired state is
deleted. The integration currently does not have a preserve-unmanaged mode.
Scope the configured Artifact Keeper instance accordingly.

## Current PACT model

The Kubernetes `registry` resource maps to an Artifact Keeper repository. An
access lists principals and the `read`, `write`, `delete`, and `admin` actions.
PACT resolves usernames and repository names to Artifact Keeper IDs before
creating permissions. Principals beginning with `svc-` are treated as service
accounts; other principals are treated as users.

PACT creates a missing `svc-` service account through the service-account API,
but its permission request uses `principal_type: user`, matching the working
TypeScript backend. The Java resolver accepts legacy `service_account`
permission entries only so reconciliation can replace them with the
`user`-typed desired permission. Other principal types and target types fail
actual-state resolution before the permission diff. The backend does not
manage identity passwords.

The HTTP permission API is more generic than the current PACT model: permission
requests contain `principal_type`, `principal_id`, `target_type`, `target_id`,
and `actions`, all represented by generic strings/IDs in the API.

## Artifact Keeper 1.5.1 compatibility

The permission create/list JSON contract in PACT matches the tagged Artifact
Keeper 1.5.1 server API: create uses `principal_type`, `principal_id`,
`target_type`, `target_id`, and `actions`; list returns those fields under
`items` and `pagination.total_pages`. The upstream request requires principal
and target IDs to be UUIDs. PACT resolves names to IDs before creating
permissions. A local HTTP contract test protects these field names and shapes.

This confirms schema compatibility, not live behavior of every deployment.
The opt-in integration test should be run against the actual deployed server
version and with a token that has permission-write scope and administrator
authorization. The checked server implementation is pinned at
[Artifact Keeper 1.5.1 permissions handler](https://github.com/artifact-keeper/artifact-keeper/blob/25d498934aca6887cf27726bfa3e2c748dbdeb8e/backend/src/api/handlers/permissions.rs).

## Upstream capabilities and model roadmap

The following capabilities were verified in the upstream Artifact Keeper
backend source checked on 2026-10-05. These authorization capabilities are
distinct from the permission API JSON schema above; their availability and
behavior must still be validated against the deployed Artifact Keeper version
before adding PACT support:

- **Repository scope** is used for content authorization.
- **Project scope** exists, and project permissions can be inherited by
  repositories assigned to that project.
- **Group scope** is used for group administration and membership operations.
- **System scope** is used for system-wide administrative checks. Upstream
  represents it with target type `system` and a nil-UUID sentinel.
- Upstream permission principal types include `user`, `service_account`,
  `group`, and `anonymous`.
- The migration documents `read`, `write`, `delete`, and `admin` actions.
- Current backend source includes an optional `conditions.allowed_cidrs`
  field. It is absent from the checked OpenAPI 1.10.1 spec, so it is
  version-dependent.

Potential follow-up work for the PACT model:

- Decide whether to expose project, group, and system targets in `DataAccess`
  and whether they belong in a common hierarchy or separate target branches.
- Decide which upstream principal types PACT should support, how group
  membership affects effective access, and whether anonymous access is
  appropriate for this product.
- Verify and document per-artifact/package authorization. Upstream's original
  permission schema names an `artifact` target and its generic resolver can
  match arbitrary target strings, but an active individual-artifact
  authorization enforcement path was not verified. Do not advertise
  artifact-level access until that behavior is confirmed for supported server
  versions.
- Verify the exact action set and inheritance rules for each target type and
  server version rather than assuming the repository action set applies
  everywhere.
- Evaluate `allowed_cidrs` and other conditional grants against the deployed
  API version before defining a PACT contract.
- Extend actual-state resolution and diffing to every supported principal and
  target type before enabling full-scope reconciliation for that expanded
  model. Unknown actual permission types currently cause reconciliation to
  fail.

## Primary references

These links pin the source versions used for the capability inventory:

- [Permission service: targets, principals, group/project inheritance and
  conditions](https://github.com/artifact-keeper/artifact-keeper/blob/4ff21f54b21aa847d499a0fb95494f75eee9905a/backend/src/services/permission_service.rs)
- [Permission handlers: CRUD validation and authorization](https://github.com/artifact-keeper/artifact-keeper/blob/4ff21f54b21aa847d499a0fb95494f75eee9905a/backend/src/api/handlers/permissions.rs)
- [Project permission checks](https://github.com/artifact-keeper/artifact-keeper/blob/4ff21f54b21aa847d499a0fb95494f75eee9905a/backend/src/api/handlers/projects.rs)
- [Group permission checks](https://github.com/artifact-keeper/artifact-keeper/blob/4ff21f54b21aa847d499a0fb95494f75eee9905a/backend/src/api/handlers/groups.rs)
- [Original permission-target schema](https://github.com/artifact-keeper/artifact-keeper/blob/4ff21f54b21aa847d499a0fb95494f75eee9905a/backend/migrations/018_groups_permissions.sql)
- [Artifact Keeper API OpenAPI specification](https://github.com/artifact-keeper/artifact-keeper-api/blob/17593394ca921fe3c864e795244af8a0562a456a/openapi.yaml)

The OpenAPI specification is version 1.10.1 and does not define an enum for
`target_type`; an open string field does not prove that a particular target
type is enforced by the service. Confirm capabilities and destructive
reconciliation behavior against the exact server release before expanding
PACT's model.
