# Working on PACT

PACT is a Java 21 / Maven plugin project for declarative access reconciliation.
Read `README.md`, `BACKEND_DEVELOPMENT.md`, and the relevant module README before
changing a backend. Read `INTEGRATION_TESTS.md` before running live tests.

## Architecture

- `pact-api` contains the shared state and plugin interfaces; `pact-core` routes
  by configured backend ID and coordinates prepare/apply/compensation.
- Keep backend-specific behavior inside its plugin. A backend ID is an arbitrary
  configured instance name, not a plugin type or a hard-coded service name.
- Follow the existing backend layers: pure compiler/validation, immutable model,
  read-only reference resolution, diff/synchronization, narrow client interface,
  and HTTP/JDBC implementation. The backend coordinates these responsibilities.
- Resolve and validate the complete desired state before deleting or creating
  permissions. Reject missing or ambiguous names; never pick the first fuzzy
  match. Preserve target type and scope when converting names to IDs.
- Desired state is authoritative within the documented backend scope. Do not
  silently introduce preserve-unmanaged behavior or broaden that scope.
- Preparation must not mutate external state. Core can compensate transactions
  whose apply never ran or partially failed. Cross-service rollback is best
  effort, not an atomic transaction; created identities can outlive grants.

## Compatibility and configuration

- Preserve the original Artifact Keeper declaration format: arbitrary
  `<backend_id>.name` identifies a repository, `access.users` may mix human
  usernames and `svc-*` service-account names, and missing `svc-*` accounts are
  ensured. Existing server metadata determines the account's wire principal type.
- Keep backend API compatibility explicit through `legacy` / `current` profiles.
  Do not retry a failed write with a different schema or hide failed catalog reads
  behind an empty result. Document version-dependent capabilities.
- Planned follow-up: retire Artifact Keeper 1.5.1 in a separate release, use the
  current API contract by default, and remove legacy branches and their CI matrix
  entry together with the corresponding documentation. Keep that removal separate
  from the name-resolution change.
- Resource names, IDs, group membership, and actual authorization enforcement
  are distinct concepts. Permission CRUD does not prove content authorization.
- State-provider changes must preserve other backends' native target hierarchies
  and permission structures. Keep provider and backend examples consistent.
- Artifact Keeper uses flat `access.allowed_cidrs` (filesystem:
  `attributes.allowed_cidrs`). Do not copy Ranger's nested `conditions` structure
  into Artifact Keeper declarations; only the HTTP adapter uses the server's
  `conditions` JSON envelope.

## Verification

- Use JDK 21 with preview enabled by the Maven configuration. On Windows, use a
  real JDK `JAVA_HOME` if the default Java shim cannot run the build.
- Run focused compiler, resolver, client, and backend tests for changed behavior:
  `mvn -B -pl <changed-modules> -am test`. Run `mvn -B test` for shared changes.
- Cover name/ID equivalence, ambiguity and missing references before writes,
  pagination, idempotency, revocation, and partial-failure compensation.
- Live tests are opt-in (`-Dpact.integration=true`). Use only disposable services
  and separate Compose project names for server versions; never downgrade a test
  database or run destructive fixture cleanup against a shared/production system.
- A full backend IT run includes Ranger HDFS/Ozone/Trino/Kafka, Artifact Keeper,
  PostgreSQL, and Elasticsearch. Distinguish it from focused Artifact Keeper IT.
- Filesystem E2E applies desired and empty state through the same application
  instance, then checks that the actual backend grants were removed.
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

## Workspace and releases

- Preserve unrelated working-tree and staged changes. Stage/commit explicit
  owned paths; do not reset or include another change accidentally.
- Never log credentials, tokens, password values, or secret-bearing snapshots.
- Docker images are published by `.github/workflows/publish-image.yml` from a
  new `image-v<version>` Git tag. Publish when requested, after the required
  checks pass. Never move an already published tag; verify the Actions result
  and registry manifest before reporting publication complete.
