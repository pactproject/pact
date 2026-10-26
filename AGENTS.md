# Working on PACT

PACT is a Java 21 / Maven plugin project for declarative access reconciliation.
These instructions cover both new backends and changes to existing plugins.

## Before making changes

- Read `README.md`, `BACKEND_DEVELOPMENT.md`, the affected backend README,
  its factory/config, compiler/resolver, synchronizer, client, and tests.
  Read `TESTING.md` for the shared test contract and current coverage map.
  Read `INTEGRATION_TESTS.md` before running live tests. For Elasticsearch,
  also consult `pact-elasticsearch/OPERATOR_GUIDE.ru.md`.
- Inspect `git status` and existing diffs first. Work may already be in progress,
  including in this file; preserve unrelated staged and unstaged changes.
- Trace the public declaration through its state provider, `pact-api`, core,
  backend and protocol adapter. A working direct client call alone does not
  establish that a DataAccess or filesystem declaration works end to end.
- Distinguish required behavior, current implementation, and known gaps. When
  docs, code, and tests disagree, report the concrete discrepancy. Ask the user
  about unresolved authorization semantics; continue independent work without
  guessing destructive behavior. Do not treat an existing bug as a template.

## Architecture

- `pact-api` contains the shared state and plugin interfaces; `pact-core` routes
  by configured backend ID and coordinates prepareTransaction/apply/compensation.
- `pact-app` validates configuration, loads plugins, creates clients, and runs
  either a snapshot provider or a long-running managed provider. Backend and
  provider factories are discovered through Java `ServiceLoader`.
- Keep backend-specific behavior inside its plugin. A backend ID is an arbitrary
  configured instance name, not a plugin type or a hard-coded service name.
  Support several instances of the same plugin; keep their state, credentials,
  ownership markers and caches separate. Do not add a core dispatch branch for
  a new service. The Kubernetes compiler's existing `registry` special case is
  a compatibility path, not a pattern for new backend IDs.
- Follow the existing backend layers: pure compiler/validation, immutable model,
  read-only reference resolution, diff/synchronization, narrow client interface,
  and HTTP/JDBC implementation. The backend coordinates these responsibilities.
- Resolve and validate the complete desired state before deleting or creating
  permissions. Reject missing or ambiguous names; never pick the first fuzzy
  match. Preserve target type and scope when converting names to IDs.
- Desired state is authoritative within the documented backend scope. Do not
  silently introduce preserve-unmanaged behavior or broaden that scope.
  The required default for all backends is `authoritative`; see the current
  backend scope and explicit compatibility modes below. Changes to defaults
  require documented migration, not an incidental documentation/refactor fix.
- Preparation must not mutate external state. Core coordinates compensation;
  rollback has the semantics of applying the previous agreed declarative state.
  Backends may optimize restoration while preserving that result. Core calls
  rollback only for applications that started, including the one that failed
  partway through. Cross-service restoration is best effort, not an atomic
  transaction; created identities can outlive grants.

## Shared state and provider contracts

- `PactState` contains immutable sets of `Access` and `Identity`. An `Access`
  has one principal, a `Resource(backendId, target)` with string-valued target
  fields, and typed attributes from `api.value.Value`. Preserve backend-native
  hierarchies instead of flattening them into one resource string. Arrays of
  attributes become sets; never depend on declaration iteration order.
- `Identity` is separate from grants and keyed by backend ID/principal.
  Conflicting identities fail. Define whether the plugin supports identity-only
  state, ensure/create, passwords and removal; unsupported requests must not be
  silently accepted. An access principal and the credential PACT connects with
  have different responsibilities.
- `Backend.validateIdentities` rejects non-empty identity declarations by
  default with checked `BackendFeatureNotImplementedException`. Core calls it
  before application; identity-capable plugins must explicitly implement this
  validation. Ranger and Artifact Keeper reject explicit identities; their
  existing access-driven principal ensure behavior remains supported.
- Identity equality uses ensure, password source and version, not password
  plaintext. Preserve `withoutSecrets()` / `withoutPassword()` behavior.
  Use `SecretValue`, reveal it only at the client boundary, and do not retain
  plaintext in applied state, status, logs or durable snapshots. Password
  changes cannot be compensated when the service cannot reveal the old value.
- Filesystem input is `accesses[].principal`, `resource.backendId`,
  `resource.target`, and `attributes`. Its current provider loads accesses only;
  it has no identity parser, target-list expansion or durable applied-state
  store. Do not advertise those capabilities without implementing them.
- Kubernetes input is `spec.resources[]` with exactly one backend-ID location
  plus `access`; `users` expands into individual accesses. Generic access fields
  other than `users` pass through as typed attributes. Target lists expand to
  a Cartesian product of concrete targets. New plugins should work through
  this generic path with arbitrary IDs.
- Kubernetes `spec.identities` is independent of grants. Secret references are
  resolved in the DataAccess namespace; resource-version changes trigger
  rotation on resync. Secret RBAC should use named `get` with `resourceNames`,
  not cluster-wide Secret listing/watching. Identity ownership is exclusive
  across DataAccess resources for each backend/principal.
- Explicit identities default to `ensure: true`; `ensure: false` requires an
  existing account. Select exactly one `backend: id` or non-empty
  `backends: [id1, id2]`. The list expands to separate backend/principal
  identities with the same options and Secret reference; conflicts and
  ownership are checked after expansion. Preserve legacy singular input.
- The controller reconciles aggregated state across resources, preserves the
  last successful `status.lastAppliedSpec` on failure, restores it on startup,
  and removes finalizers only after cleanup succeeds. Preserve status snapshot
  compatibility and unknown fields in the CRD when extending declarations.
  Removing one declaration must retain access still requested by another.
- Core groups both accesses and identities by backend ID, rejects unknown IDs,
  and skips equal previous/desired states. Only IDs present in previous or
  desired state or retained recovery scope participate; an unused configured backend is not sent an empty
  state at startup. There is no unconditional external-drift repair on an
  unchanged declaration. Successful transitions retain secret-free applied
  state; failed transitions do not advance it. Restore is startup-only.

## Designing and implementing a backend

Before coding, record the following contract in the module README and tests:

1. Configured scope, ownership/collision rules, authoritative default, optional
   preservation mode, and effects of empty state or the final declaration's
   removal. State how previous state keeps removed targets in cleanup scope.
2. Supported target hierarchy, exact names versus IDs, privilege catalog,
   inheritance, wildcards, deny rules and aggregation. Distinguish explicit
   grants from effective authorization through groups, ownership or defaults.
3. Principal existence requirements, identity-only behavior, ensure/create,
   credential generation/rotation and irreversible effects. Never turn account
   removal into an implicit consequence of removing access.
4. Supported server/API versions, required service-account privileges,
   pagination, timeouts, retry semantics, concurrency/consistency limits and
   whether a single writer is required. Do not assume writes are safe to retry
   after a lost response or that an incomplete catalog means an empty scope.
5. Validation and write ordering, failure reporting, and transaction recovery:
   how rollback restores previous agreed declarations, which cleanup scope
   must be retained, and whether snapshots help optimize the implementation.
   Document irreversible effects; snapshots are not mandatory and must not
   replace the previous agreed declaration as the restoration target.

Implementation rules:

- Use a pure deterministic compiler, immutable domain model, read-only
  reference resolver, diff/synchronizer, narrow `api` client, and `client`
  protocol adapter where needed. Keep configuration in a validating config
  type and backend orchestration small. Do not add empty layers or broadly
  reorganize an existing backend merely to match directory names.
- Validate target shape, backend ID, supported attributes/config keys,
  privilege combinations and references for the complete desired state before
  mutations, including identity writes. Check externally supplied privilege
  catalogs read-only; do not hard-code an obsolete privilege list.
- Resolve exact references to canonical typed IDs within the correct parent
  scope. Load all catalog pages; reject missing/ambiguous matches and malformed
  responses. Name/ID-equivalent declarations should produce no rewrite.
  Define cache lifetime so renames and newly created objects become visible.
- Normalize actual/desired objects before diffing. Aggregate overlapping
  declarations deterministically; make create/update/delete/no-op and duplicate
  handling explicit. Verify scope and ownership before destructive operations.
- Keep external SDK types and transport/SQL construction in adapters; expose
  domain values to compilers and synchronizers. Quote SQL identifiers, bind
  values where supported, and encode URL components safely. Never use fuzzy
  lookup, schema-fallback writes or swallowed read errors as recovery.
- Surface operational failures as `BackendException` (normally
  `ValidationException` or `BackendOperationException`) with a useful safe
  cause. Core's compensation catches `BackendException`; an unchecked adapter
  failure can bypass that path. Sanitize nested errors and request bodies too.
- Release persistent resources through `Backend.close()`; close must not revoke
  grants or delete identities. The application closes backends before unloading
  plugins and cleans up already-created clients on later initialization failure.
  A factory must release resources it allocates if its own construction fails.

## Transactions and compensation

- After rollback failure, the next reconciliation applies the current source
  declaration even if it equals the previous agreed state or is empty. Core
  retains secret-free states from every uncertain transition as cleanup scope;
  do not replace the desired target with those states or merge their grants into
  desired permissions. Only failed compensation overrides the equality shortcut.
- `prepareTransaction(previous, desired, recoveryStates)` must remain read-only
  and retain recovery scopes for both application and its compensation. The API
  default explicitly rejects non-empty recovery scopes unless a plugin supports
  them. Global-scope plugins may delegate to ordinary preparation; scoped plugins
  must include attempted databases/users/default-privilege scopes and assignments.
- Recovery context currently lives only in core memory. It is not written to
  Kubernetes status or persisted for filesystem runs; process restart loses it.
  Successful application or compensation clears the relevant context. Never
  claim restart recovery of failed compensation without implementing persistence.
- Retain `Backend.prepareTransaction(previous, desired)` and `BackendTransaction` with
  `apply()` / `rollback()` as the plugin contract. Core decides when to apply
  and compensate, maintains the previous successful state, and controls order.
  A plugin restores only its own documented scope when core invokes rollback;
  it must not orchestrate recovery of other backends.
- The preparation method was renamed from `prepare` to `prepareTransaction`.
  Rebuild plugins that override the old method against the updated API and
  migrate their overrides/call sites; do not deploy old plugin binaries with
  this API and assume their custom preparation will still be dispatched.
- `prepareTransaction` returns a transaction, not just a validation result. It may perform
  read-only validation, reference resolution and planning. Detectable errors
  should stop the transition before any backend writes, but successful
  preparation does not guarantee successful apply: references, authorization
  or availability can change and writes can fail partway through. Never create
  accounts, update passwords or alter grants during preparation.
- The default `prepareTransaction` defers `apply(desired)` and implements rollback through
  `apply(previous)`. Simple plugins may use this mechanism when `apply` fully
  and idempotently reconciles their scope and retains enough context to remove
  partially applied changes. Backends may optimize the rollback implementation,
  but it must have the same reconciliation result as applying previous state,
  within the documented scope and irreversible-side-effect limitations.
- Reapplying previous desired state restores the previous agreed declaration;
  it need not reproduce every external change made since that declaration.
  An actual-state snapshot is not mandatory. Override `prepareTransaction` for explicit
  cleanup scope, read-only preflight validation, or optimized restoration.
  Snapshots can assist planning, but arbitrary pre-call external state is not
  the restoration target. Ranger reapplies compiled previous policies;
  PostgreSQL reapplies previous grants/defaults across the transition scope;
  Elasticsearch uses its ordinary synchronizer on the reversed transition.
- Core prepares every changed backend before applying any, in deterministic
  backend-ID order. If preparation fails, no apply has started and no rollback
  runs. A failing prepareTransaction must leave no mutations or leaked resources of its own.
- Before invoking a transaction's apply, core records it as started. On apply
  failure, core rolls back only started transactions in reverse order,
  including the failing transaction: writes may have partially succeeded or
  their success response may have been lost. Prepared but unstarted
  transactions are never rolled back by core.
- Retain the union of previous and attempted target scopes for restoration.
  If a failed transition added grants in database B while moving from A to B,
  restoring A must also remove attempted grants in B. `apply(previous)` alone
  is insufficient if the plugin derives scope only from its argument. Direct
  `apply(empty)` on a fresh backend need not know the scope captured by
  `prepareTransaction(previous, empty)`.
- Keep plans, snapshots and apply progress isolated per transaction; do not
  depend on mutable working state left by a different call. Test recovery
  after partial apply and retry; verify optimized recovery against previous
  agreed state rather than assuming a snapshot is an equivalent substitute.
- Define startup history explicitly: filesystem history is process-local;
  Kubernetes restores successful declarations from status. An initially empty
  history is not evidence of an initially empty external service. Restoration
  cannot undo unknown old passwords; retained created accounts and other
  irreversible effects must be documented. The default prepareTransaction does not
  validate or resolve desired state until apply; do not claim otherwise.

## Adding a plugin to the distribution

1. Add `pact-<name>` to the root Maven modules. Follow existing group/package
   conventions and depend on `pact-api` at `${pact.api.version}`. Root app/API
   and individual plugins have separate versions; do not mechanically bump all.
2. Implement `BackendFactory.type()` and `create(id, Map<String, String>)`;
   validate config/defaults during creation without mutating the service.
   Register the fully qualified factory name in
   `src/main/resources/META-INF/services/io.github.pactproject.api.BackendFactory`.
   Plugin types must be unique; instance IDs must remain arbitrary.
3. Add the module to Dockerfile dependency collection and plugin-JAR copying.
   Plugins are ordinary JARs; runtime dependencies must also be available.
   The loader scans top-level JARs in the plugins directory using a shared,
   parented classloader; do not assume per-plugin dependency isolation. Check
   dependency conflicts and packaged ServiceLoader discovery.
4. Update module configuration/access examples for filesystem and Kubernetes,
   root component docs, and integration runner/Compose/CI fixtures as applicable.
   A module passing Maven tests is not proof it is included in the image.

## Updating an existing backend

- Preserve accepted declarations, aliases, generated names, ownership metadata,
  configuration defaults and snapshot formats unless the requested change
  explicitly includes a migration. Add regression cases for the old contract.
- Separate structural cleanup from changes to deletion scope, default mode,
  identity creation, effective permissions or API compatibility. Describe the
  before/after behavior, upgrade steps and retained irreversible effects.
- For a server/SDK upgrade, verify protocol shapes and real authorization
  behavior for the supported version; keep compatibility logic in its adapter.
  Do not change shared providers to compensate for one plugin's wire format.
- Keep module docs, provider examples, fixtures and version references aligned.
  Test final-declaration removal and restart recovery when previous state or
  persisted status determines revocation scope.

## Compatibility and configuration

### Ranger

- Load the configured service's type and ServiceDef; derive resource hierarchy,
  recursive support, privilege names, masks and row filters from that definition.
  Backend ID need not equal ServiceDef type. Do not add service-name allow-lists.
- Default scope is every policy in the configured service. `managed-only: true`
  limits reconciliation/snapshots to the `managed` label; handle name collisions
  without adopting unrelated policies. Missing policy users are ensured with
  random passwords that are neither retained nor logged; users survive cleanup.
- Preserve allow/deny, `dataMask`, `rowFilter`, recursive target flags,
  conditions, validity schedules and override semantics. See its compiler tests
  before changing policy identity or aggregation. Live Ranger API tests do not
  establish enforcement by HDFS/Ozone/Trino/Kafka applications.

### PostgreSQL

- Preserve database/schema/table/column and sibling sequence/function/procedure
  targets. Names are exact; wildcards are unsupported. Routine references need
  exact argument signatures, including `()`; resolve overloads through catalogs.
  Privileges at different hierarchy levels are independent.
- Default `reconciliation-mode: authoritative` requires superuser authority
  and reconciles direct
  ACLs across grantors, using `SET ROLE` and cascading revokes. It does not remove
  object ownership or role membership. Built-in PUBLIC privileges participate
  as ordinary ACLs; remove them when undeclared. The removed
  `preserve-default-public-privileges` key must fail config validation.
- Explicit `grantor` retains the compatibility scope: only ACLs issued by the
  configured login, excluding PUBLIC/other grantors. Databases in previous OR
  desired access state determine cleanup scope. Use a dedicated grantor and
  do not overlap independently reconciled scopes.
- `defaultPrivileges` is implemented: creator/schema/type-scoped native defaults
  plus existing creator-owned objects; exact-object declarations override
  default-derived grants. Preserve separate default-ACL snapshot/cleanup scope.
  PostgreSQL RLS / `rowPolicy` in the README is a proposal, not implemented input.
- Identities can ensure roles independently of grants and rotate Secret-backed
  passwords. Existing role attributes stay unchanged; accounts are not dropped.
  Identity changes precede grants and are not reversed by grant compensation.
  JDBC reconciliation is atomic per database, not across databases. Check actual
  ACL results: successful SQL execution alone does not prove a grant took effect.

### Artifact Keeper

- Preserve the original Artifact Keeper declaration format: arbitrary
  `<backend_id>.name` identifies a repository, `access.users` may mix human
  usernames and `svc-*` service-account names, and missing `svc-*` accounts are
  ensured. Existing server metadata determines the account's wire principal type.
- Artifact Keeper uses only the current API contract, tested against 1.11.1.
  Omitted `api-profile` means `current`; explicit `legacy` must fail during
  backend initialization before API calls. Do not restore the removed 1.5.1
  fallback, retry writes with another schema, or hide failed catalog reads
  behind an empty result. Image 1.0.6 is the last release supporting 1.5.1.
- Preserve normalization of stored user-typed service-account grants on current
  servers. Their stored representation is distinct from supporting the old API.
- Resource names, IDs, group membership, and actual authorization enforcement
  are distinct concepts. Permission CRUD does not prove content authorization.
- State-provider changes must preserve other backends' native target hierarchies
  and permission structures. Keep provider and backend examples consistent.
- Artifact Keeper uses flat `access.allowed_cidrs` (filesystem:
  `attributes.allowed_cidrs`). Do not copy Ranger's nested `conditions` structure
  into Artifact Keeper declarations; only the HTTP adapter uses the server's
  `conditions` JSON envelope.
- Scope is the full permission collection returned by the configured instance,
  with no preserve-unmanaged mode. Preserve every target type, including unknown
  types addressed by UUID. Resolve artifacts by repository and full path plus
  optional version, never basename; system/global use the nil UUID. Group
  grants do not create groups or manage membership. Anonymous is read-only on
  repository/project. Preserve all physical permission IDs when normalizing
  duplicates so revocation removes every matching representation.

### Elasticsearch

- Elasticsearch uses the official Java client 9.2.3 with SDK types confined to
  its client adapter. Preserve `index: pattern` / `permissions.indices`; cluster
  declarations use `cluster: "*"`, `permissions.cluster` and optional existing
  `roles`. Validate privilege and role references before writes. Never edit
  referenced shared/built-in role definitions or manage built-in users.
- Elasticsearch defaults to `authoritative`, removing undeclared assignments only for users in
  previous/desired access or identity scope. Identity-only ensure must work;
  generated passwords are creation-only and must not reset manual passwords.
  Private CA configuration must retain hostname verification and remain local
  to the backend client. Release persistent client resources on shutdown.
- Explicit `managed` preserves foreign role assignments for compatibility;
  removing previously declared role assignments still revokes them. Keep the
  connecting administrative user outside authoritative identity/access scope.
- Generated roles are deterministic per backend ID/principal with verified
  ownership metadata; reject collisions. Union direct grants and existing role
  assignments, preserve user profile/enabled fields, and never delete accounts.
  `principal-mode: ensure` is still required for account creation; explicit
  identity declarations default to ensure true and may opt out with false.
  Use one writer per overlapping role/user scope, especially in authoritative
  mode; there is no conditional native-user role-assignment update.

## Verification

- Follow `DIAGNOSTICS.md` for phase events, backend/native action details, levels,
  correlation, timings, truthful counts and credential masking. Backend-specific
  details belong in plugin logs; core supplies common lifecycle events.
- Lost-response/partial-write recovery and retry are required backend scenarios,
  including recovery to the latest source declaration after failed rollback.
  Test completeness for every catalog the plugin reads, not merely pagination:
  later-page errors, duplicates, malformed envelopes, inconsistent totals and
  premature termination must fail before writes. Native SQL/SDK catalogs need
  equivalent fail-closed coverage without inventing pagination they do not use.
- `TESTING.md` is the canonical backend test checklist (B01–B11 and applicable
  C01–C05), suite inventory and coverage/gap map. Keep its concrete test
  references and module README aligned with changes. Run
  `python integration/scripts/check-test-contract.py` to catch stale file/method
  references and unlisted test classes; it does not verify assertion semantics.
- Use JDK 21 with preview enabled by the Maven configuration. On Windows, use a
  real JDK `JAVA_HOME` if the default Java shim cannot run the build.
- Run focused compiler, resolver, client, and backend tests for changed behavior:
  `mvn -B -pl <changed-modules> -am test`. Run `mvn -B test` for shared changes.
- Cover name/ID equivalence, ambiguity and missing references before writes,
  pagination, idempotency, revocation, and partial-failure compensation.
- Compiler/model tests cover invalid and unsupported fields, hierarchy,
  privilege combinations, backend-ID mismatch and deterministic aggregation.
  Diff tests cover create/update/delete/no-op, empty state, duplicates, ownership
  collisions, scope boundaries and each optional preservation mode.
- Recovery tests cover read-only preparation, failure in a later prepareTransaction,
  absence of rollback calls for unstarted transactions, partial writes/lost responses,
  retries, cleanup across old/attempted scopes, irreversible identities and
  password rotations. Preserve snapshot-isolation tests where the current
  implementation still uses snapshots. Use fake clients to assert
  absence of writes on validation failure; use real services for semantics a
  fake cannot establish. Test core coordination when changing shared contracts.
- Adapter/config/plugin tests cover request/response mapping, pagination,
  authentication, errors/timeouts, SQL/URL handling, secret redaction, defaults,
  ServiceLoader discovery and client shutdown/initialization failure cleanup.
- Live tests are opt-in (`-Dpact.integration=true`). Use only disposable services
  and separate Compose project names for server versions; never downgrade a test
  database or run destructive fixture cleanup against a shared/production system.
- A full backend IT run includes Ranger HDFS/Ozone/Trino/Kafka, Artifact Keeper,
  PostgreSQL, and Elasticsearch. Distinguish it from focused Artifact Keeper IT.
  Ranger scenarios verify its API/ServiceDefs without running the four target
  applications. Full Maven live-suite runs also include Kubernetes tests.
- Filesystem E2E applies desired and empty state through the same application
  instance, then checks that the actual backend grants were removed.
  The Compose filesystem E2E covers Ranger, Artifact Keeper, PostgreSQL and
  Elasticsearch. Elasticsearch checks real read/write enforcement, a privilege
  update, an unrelated index boundary, empty-state revocation and generated-role
  deletion. Its uniquely named user/indices are fixture setup/cleanup; filesystem
  input still has no identity parser.
- The `kubernetes` Compose profile verifies named Artifact Keeper declarations
  through a real disposable k3s API and checks persisted status across separate
  controller JVMs. It requires the `current` profile and an empty, dedicated
  integration CRD; never substitute a user's working Kubernetes cluster.
- Current-profile enforcement tests use independent TCP peers for CIDR checks,
  real artifact downloads for project boundaries, and a one-shot HTTP fault
  after a successful write for compensation. Artifact-only grants in 1.11.1
  do not satisfy the repository download gate; preserve that distinction.
- Update module documentation and tests together. State what was executed and
  what was skipped; do not claim enforcement that was only tested through CRUD.
- For documentation-only changes, verify paths, examples and consistency with
  source/tests; a full Maven/live run is unnecessary. For code changes, report
  exact commands, outcomes, skipped live scenarios and remaining limitations.

## Workspace and releases

- Treat readers of PACT logs as administrators of every configured backend.
  Users/principals, resource names and IDs, ACLs/policies, declarations, SQL,
  request/response details and operational errors may be logged for diagnosis;
  do not hide them merely because they describe backend state. Choose useful
  log levels and retain actionable error details.
- Passwords (including generated/new identity passwords), API tokens/keys and
  Authorization header values are the exceptions: mask them in messages,
  bodies, nested errors and snapshots. Sanitize diagnostic details rather than
  dropping whole responses; never attach a raw cause that reintroduces secrets.
  `DiagnosticSanitizer` masks known credential values and named secret fields;
  adapters must pass connection credentials and passwords from the current write.
- Preserve unrelated working-tree and staged changes. Stage/commit explicit
  owned paths; do not reset or include another change accidentally.
- Never log credentials, tokens, password values, or secret-bearing snapshots.
- Docker images are published by `.github/workflows/publish-image.yml` from a
  new `image-vN` Git tag, where N is the positive integer PACT release number.
  Starting with 8 (after historical image 1.0.7), keep the root Maven version,
  `pact.api.version`, image tag, Helm `appVersion` and default `image.tag` aligned.
  Run `python integration/scripts/check-release.py N` before tagging.
  Publish when requested, after the required checks pass. Never move an already
  published tag; verify the Actions result
  and registry manifest before reporting publication complete.
- Plugins retain their independent module versions; the Helm chart package
  version remains independent SemVer. Plugin JARs are not independently
  published. A new image contains plugins from its source revision; align Helm `appVersion` and
  default `image.tag` with the intended image when updating deployment defaults.
