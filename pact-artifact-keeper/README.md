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

The existing `<backend_id>.name` form works with any configured Artifact Keeper
backend ID, and `users` can mix human names and `svc-*` service accounts without
adding `principal-type`. For example:

```yaml
resources:
  - company-registry:
      name: repository-a
    access:
      - users: [alice, svc-build]
        permissions: [read]
```

The backend accepts `name` as a repository-name alias and `permissions` as an
`actions` alias. Conflicting `actions` and `permissions` are rejected.

An access lists principals and the `read`, `write`, `delete`, and `admin` actions.
PACT resolves usernames and repository names to Artifact Keeper IDs before
creating permissions. Existing principals are classified using server account
metadata, even when their username starts with `svc-`. The backend requires the
boolean `is_service_account` field from `/users`; it does not fall back to a
separate service-account listing.
An absent principal beginning with `svc-` is created through the service-account
API; an absent principal with any other name is rejected. The backend does not
manage identity passwords or API tokens, and retains created service accounts
when their permissions are removed.

Within PACT, service-account grants always have the logical type
`service_account`, which is also the type used for new permission writes.
Previously stored `user`-typed grants naming a service account are normalized
on read and preserved when their actions already match. If both wire types
exist, revocation deletes every matching permission ID. Missing or invalid
`is_service_account` metadata fails state loading instead of guessing an
existing account's type.

All four principal types are supported: `user`, `service_account`, `group`,
and `anonymous`. Set `principal-type` in access attributes when needed;
user/service-account classification for existing accounts still comes from
server metadata. Group principals accept group names or UUIDs; PACT does not
create groups or manage memberships. Anonymous principals use the nil UUID
and may only grant `read` on repository or project targets.

Optional `allowed_cidrs: [...]` directly in the
access entry restricts a grant by client IP. PACT preserves this restriction when
reading, diffing, creating, and compensating grants. Empty CIDR lists, malformed
CIDRs, unknown access attributes, and conflicting CIDR restrictions for the same
principal and target are rejected. Nested `conditions` is not accepted in Artifact
Keeper declarations. Anonymous and IP-restricted desired grants require `current`.

Example Kubernetes `DataAccess.spec`:

```yaml
resources:
  - registry:
      type: project
      name: payments
    access:
      - users: [release-readers]
        principal-type: group
        permissions: [read]
        allowed_cidrs: [10.0.0.0/8]
```

### Names and explicit IDs

Names are exact, case-sensitive references. PACT resolves every desired reference
before changing permissions and rejects missing or ambiguous matches. A name
and an ID resolving to the same object produce the same grant; switching forms
does not recreate it. Catalogs are loaded only when needed and only cached within
one reconciliation, so renames and new objects are visible on the next apply.

| Object or subject | Named form | Explicit ID form |
| --- | --- | --- |
| Repository target | `{name: releases}` or `{type: repository, name: releases}`; repository key or unique display name | `{type: repository, id: <UUID>}` |
| Project target | `{type: project, name: payments}`; project key or unique display name | `{type: project, id: <UUID>}` |
| Group target | `{type: group, name: developers}` | `{type: group, id: <UUID>}` |
| Artifact target | `{type: artifact, repository: releases, path: org/app.jar}` | `{type: artifact, id: <UUID>}` |
| User/service-account subject | `users: [alice, svc-build]` | UUIDs in `users`; existing account metadata determines type |
| Group subject | `users: [developers]` with `principal-type: group` | UUIDs with the same principal type |

Artifact `name` is also accepted in place of `path`, but it denotes the complete
relative artifact path, not a basename search. Repository scope is required.
An optional `version` selects a revision number or label on version-enabled
repositories; without it the server resolves HEAD. Different repositories and
versions are resolved separately. No artifact-name lookup uses fuzzy search.
If a revision selector is supplied, the returned revision/label must match it;
PACT rejects a response that silently fell back to HEAD.

```yaml
resources:
  - company-registry:
      type: artifact
      repository: releases
      path: org/example/app/1.0/app.jar
    access:
      - users: [release-readers]
        principal-type: group
        permissions: [read]
```

`{type: system}` and `{type: global}` use the nil UUID without a catalog lookup;
their optional `name` must equal the type. Unfamiliar target types still require
an explicit UUID. Anonymous subjects remain the nil principal with read-only
repository/project grants. Resolving a name never creates groups, projects,
repositories, or artifacts, and never adds group memberships.

Project names, group names, and repository-qualified artifact paths with
optional `version` selectors use the current API contract. Explicit IDs
continue to avoid optional catalog reads.

Names are configuration references, not durable snapshots of an object ID.
Reusing a name for another object changes what the next reconciliation targets;
use an explicit ID when the declaration must remain bound to one object.

Filesystem access uses `principal`, `resource.backendId`, `resource.target`,
and `attributes` (`actions`, `principal-type`, and optional `allowed_cidrs`), as
shown in the [filesystem fixture](../integration/e2e/desired-state.yaml).

Existing permissions of all target types participate in the normal authoritative
diff, including deletion when absent from desired state. Add grants you intend
to retain to desired state before upgrading; no manual deletion is needed to
work around the former `Unsupported Artifact Keeper target type: artifact`
error.

## API compatibility and configuration

Starting with image **1.0.7**, PACT supports only the current API contract,
tested against Artifact Keeper **1.11.1**. Omitted `api-profile` defaults to
`current`; explicitly setting `current` is also accepted. The retired
`api-profile: legacy` throws an exception during backend initialization,
before any API request. Other unknown profile values are rejected as well.

Image **1.0.6** is the last release supporting Artifact Keeper 1.5.1. To migrate,
upgrade the server to 1.11.1, remove `api-profile: legacy` (or change it to
`current`), then deploy the new PACT image. Existing repository declarations,
mixed `users` lists, and stored user-typed service-account grants remain valid.

```yaml
backends:
  - id: registry
    type: artifact-keeper
    config:
      url: ${ARTIFACT_KEEPER_URL}
      token: ${ARTIFACT_KEEPER_TOKEN}
      api-profile: current
```

`per-page` remains optional and defaults to `100`. PACT does not guess API
behavior by retrying writes after `400`/`403` responses. The checked server
release is 1.11.1; compatibility with other releases requires checking their
contract and authorization behavior, not just their version number.

Service-account creation uses
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
Keeper 1.11.1 server API: create uses `principal_type`, `principal_id`,
`target_type`, `target_id`, and `actions`; list returns those fields under
`items` and `pagination.total_pages`. The upstream request requires principal
and target IDs to be UUIDs. PACT resolves named repositories, projects, groups,
artifacts, and user/service-account references before writes, and forwards
explicit generic target UUIDs directly. Current-profile requests also carry
`conditions: {allowed_cidrs: [...]}` when configured; this is the server's JSON
contract, while PACT declarations use the flat `allowed_cidrs` attribute. Local
HTTP contract tests protect these shapes.

CI and opt-in live tests cover Artifact Keeper 1.11.1,
including service-account creation, repeated reconciliation, grant revocation,
and actual access to a private repository with a service-account token. Unit
tests also cover stored user-typed service-account grant normalization and
compensation after a partially applied change. The live test deletes its private repository and revokes its
test token, retaining only the uniquely named service account. See
[integration instructions](../INTEGRATION_TESTS.md). Run the tests against the
actual deployed release before expanding compatibility claims. The permission
contract is pinned at the
[Artifact Keeper 1.11.1 permissions handler](https://github.com/artifact-keeper/artifact-keeper/blob/v1.11.1/backend/src/api/handlers/permissions.rs).

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

Additional current-profile live tests verify actual artifact downloads from
allowed/denied TCP source IPs, resistance to untrusted forwarding headers,
project inheritance across two repositories and denial outside that project,
and immediate revocation. In 1.11.1, an artifact-only grant does **not** allow
downloads from a private repository: the content route still requires a
repository/project grant. A characterization test preserves this distinction.
Another test injects a real HTTP failure after a successful permission deletion
and verifies core compensation restores actions, principal, target and CIDRs.
The Kubernetes E2E additionally checks named YAML and persisted state across
two controller JVMs. See the integration instructions for the disposable stack.

## Primary references

- [1.11.1 permission service: principal validation, target matching, project
  inheritance and conditions](https://github.com/artifact-keeper/artifact-keeper/blob/v1.11.1/backend/src/services/permission_service.rs)
- [1.11.1 permission handlers](https://github.com/artifact-keeper/artifact-keeper/blob/v1.11.1/backend/src/api/handlers/permissions.rs)
- [1.11.1 group administration](https://github.com/artifact-keeper/artifact-keeper/blob/v1.11.1/backend/src/api/handlers/groups.rs)
- [Project permission inheritance](https://artifactkeeper.com/docs/advanced/projects/)
- [Service-account access and group-target distinction](https://artifactkeeper.com/docs/advanced/service-account-access/)
