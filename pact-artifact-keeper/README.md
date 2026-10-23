# PACT Artifact Keeper backend

`pact-artifact-keeper` reconciles Artifact Keeper permissions for all target
types, including `repository`, `artifact`, `project`, `group`, `system`, and
`global`. Its configured backend instance is the authoritative source of
truth for the permissions returned by the Artifact Keeper `GET
/api/v1/permissions` endpoint: a permission absent from PACT's desired state is
deleted. The integration currently does not have a preserve-unmanaged mode.
Scope the configured Artifact Keeper instance accordingly.

## Current PACT model

The Kubernetes `registry: {name: repository-name}` form still maps to a
repository. The generic `registry: {type: artifact, id: <UUID>}` form addresses
any target type by its exact type string and UUID. In filesystem state, use
`target: {repository: repository-name}` or `target: {type: artifact, id: <UUID>}`.
PACT does not reinterpret `artifact` as a repository, or map arbitrary target IDs
to repository names. The server accepts open target strings, so PACT also
preserves unfamiliar target types.

An access lists principals and the `read`, `write`, `delete`, and `admin` actions.
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
matching permission ID. Missing or invalid account metadata
(the flag in `current`, or the separate list in `legacy`) fails state loading
instead of guessing an existing account's type.

All four principal types are supported: `user`, `service_account`, `group`,
and `anonymous`. Set `principal-type` in access attributes when needed;
user/service-account classification for existing accounts still comes from
server metadata. Group principals use group UUIDs, not names; PACT does not
create groups or manage memberships. Anonymous principals use the nil UUID
and may only grant `read` on repository or project targets.

With `api-profile: current`, optional `conditions: {allowed_cidrs: [...]}`
restricts a grant by client IP. PACT preserves these conditions when reading,
diffing, creating, and compensating grants. Empty CIDR lists, malformed CIDRs,
unknown condition keys, and conflicting conditions for the same principal and
target are rejected. Anonymous and conditional desired grants require `current`.

Example Kubernetes `DataAccess.spec`:

```yaml
resources:
  - registry:
      type: project
      id: 11111111-1111-1111-1111-111111111111
    access:
      - principals: [22222222-2222-2222-2222-222222222222]
        principal-type: group
        permissions: [read]
        conditions:
          allowed_cidrs: [10.0.0.0/8]
```

Filesystem access uses `principal`, `resource.backendId`, `resource.target`,
and `attributes` (`actions`, `principal-type`, and optional `conditions`), as
shown in the [filesystem fixture](../integration/e2e/desired-state.yaml).

Existing permissions of all target types participate in the normal authoritative
diff, including deletion when absent from desired state. Add grants you intend
to retain to desired state before upgrading; no manual deletion is needed to
work around the former `Unsupported Artifact Keeper target type: artifact`
error.

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
and target IDs to be UUIDs. PACT resolves names for repository/user references
and forwards generic target UUIDs directly. Current-profile requests also carry
`conditions` when present. Local HTTP contract tests protect these shapes.

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

## Target semantics

Supporting permission CRUD does not change Artifact Keeper's enforcement rules:

- Repository permissions govern repository content; project permissions can
  be inherited by repositories assigned to that project.
- A `group` target governs group administration. It does not add the principal
  to that group. A `group` principal refers to the group's existing members.
- System administrative checks use `type: system` with the nil UUID.
- `artifact`, `global`, and unfamiliar targets round-trip through the permission
  API. This does not establish that every content endpoint checks those grants,
  or that `global` means access to all repositories. Use the server's documented
  scope for the operation being authorized.

The live suite checks create, read, update, repeated apply, and removal for every
listed target type plus an unfamiliar target string. The current profile also
checks conditions and group/anonymous principals. Content authorization
with a service-account token is verified separately on a private repository.
These CRUD checks do not claim individual-artifact enforcement.

## Primary references

- [1.11.1 permission service: principal validation, target matching, project
  inheritance and conditions](https://github.com/artifact-keeper/artifact-keeper/blob/v1.11.1/backend/src/services/permission_service.rs)
- [1.11.1 permission handlers](https://github.com/artifact-keeper/artifact-keeper/blob/v1.11.1/backend/src/api/handlers/permissions.rs)
- [1.11.1 group administration](https://github.com/artifact-keeper/artifact-keeper/blob/v1.11.1/backend/src/api/handlers/groups.rs)
- [Project permission inheritance](https://artifactkeeper.com/docs/advanced/projects/)
- [Service-account access and group-target distinction](https://artifactkeeper.com/docs/advanced/service-account-access/)
