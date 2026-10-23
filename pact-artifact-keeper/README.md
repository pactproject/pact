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
creating permissions. Existing principals are classified using server account
metadata, even when their username starts with `svc-`. The `current` profile
reads `is_service_account` from `/users`; `legacy` obtains service-account IDs
from `/service-accounts` because 1.5.1 omits that flag from `/users`.
An absent principal beginning with `svc-` is created through the service-account
API; an absent principal with any other name is rejected. The backend does not
manage identity passwords or API tokens, and retains created service accounts
when their permissions are removed.

Within PACT, service-account grants always have the logical type
`service_account`. The API profile translates that type for permission writes.
Legacy `user`-typed grants naming a service account are normalized on read and
preserved when their actions already match. Changing profiles does not force a
rewrite of those grants. If both wire types exist, revocation deletes every
matching permission ID. Other principal types and target types fail actual-state
resolution before the permission diff. Missing or invalid account metadata
(the flag in `current`, or the separate list in `legacy`) fails state loading
instead of guessing an existing account's type.

The HTTP permission API is more generic than the current PACT model: permission
requests contain `principal_type`, `principal_id`, `target_type`, `target_id`,
and `actions`, all represented by generic strings/IDs in the API.

## API compatibility and configuration

PACT supports explicit API profiles, independently of its own plugin version:

| Server release used for compatibility tests | `api-profile` | Permission write type for service accounts |
| --- | --- | --- |
| 1.5.1 | `legacy` (default) | `user` |
| 1.11.1 | `current` | `service_account` |

The default preserves existing 1.5.1 configurations. For 1.11.1 configure:

```yaml
backends:
  - id: registry
    type: artifact-keeper
    config:
      url: ${ARTIFACT_KEEPER_URL}
      token: ${ARTIFACT_KEEPER_TOKEN}
      api-profile: current
```

`per-page` remains optional and defaults to `100`. Unknown profile names are
rejected during backend construction. Profiles are selected by the operator;
PACT does not guess API behavior by retrying writes after `400`/`403` responses.
The supported matrix covers the exact releases above, not every intermediate
or future release. Add releases to the CI matrix after checking their contract
and authorization behavior; add another profile only when a real incompatibility
requires it. Deprecate old profiles explicitly in release notes before removal.

In both checked releases, service-account creation uses
`POST /api/v1/service-accounts` with `{"name":"build"}` and returns the account's
`id` and `username` (`svc-build`). The create response does not need an
`is_service_account` field because the endpoint establishes its type.

Artifact Keeper 1.11.1 validates the principal type against account metadata on
permission writes: `user` cannot name a service account. Previously stored
`user` grants for service accounts still work, so PACT preserves them on read.
This distinction is implemented in the tagged
[1.11.1 permission service](https://github.com/artifact-keeper/artifact-keeper/blob/v1.11.1/backend/src/services/permission_service.rs).

The backend credential needs administrator authorization and the scopes required
to list users/repositories/permissions and create/delete permissions and service
accounts. In 1.11.1, an admin-owned API token must also carry `admin` (or `*`) to
retain administrator authority; narrow artifact scopes alone do not suffice.
See the tagged [authentication middleware](https://github.com/artifact-keeper/artifact-keeper/blob/v1.11.1/backend/src/api/middleware/auth.rs).
A `403` indicates an authorization problem, not a reason to switch API profiles.
A service account can be created successfully before its permission request
fails, so inspect the failed endpoint and the nested HTTP error.

Desired user/repository references are validated before permission writes.
Reconciliation retains the normal PACT transaction model: `pact-core` invokes
compensation after a failure, and this backend restores the previous desired
grants through the same profile. Compensation does not delete created accounts
and can itself fail if the external API remains unavailable or denies writes.

### Permission JSON contract

The permission create/list JSON contract in PACT matches the tagged Artifact
Keeper 1.5.1 server API: create uses `principal_type`, `principal_id`,
`target_type`, `target_id`, and `actions`; list returns those fields under
`items` and `pagination.total_pages`. The upstream request requires principal
and target IDs to be UUIDs. PACT resolves names to IDs before creating
permissions. A local HTTP contract test protects these field names and shapes.

The CI compatibility matrix and opt-in live tests cover both releases above,
including service-account creation, repeated reconciliation, grant revocation,
and actual access to a private repository with a service-account token. Unit
tests also cover legacy grant normalization and compensation after a partially
applied change. The live test deletes its private repository and revokes its
test token, retaining only the uniquely named service account. See
[integration instructions](../INTEGRATION_TESTS.md). Run the tests against the
actual deployed release before extending the supported matrix.
The original checked server implementation is pinned at
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
