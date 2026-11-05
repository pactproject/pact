# Test contract and coverage map

This is the shared testing contract for new backends and changes to existing
ones. Backend-specific scope and supported capabilities remain defined in each
module README. [BACKEND_DEVELOPMENT.md](BACKEND_DEVELOPMENT.md) defines the
implementation contract; [INTEGRATION_TESTS.md](INTEGRATION_TESTS.md) defines
live environments, isolation and commands.

Update requirements, test references and the affected module README in the
same change. A requirement below is not a claim that every existing backend
already satisfies it. Coverage describes assertions in source, not the result
of a particular run. Report execution and skipped scenarios separately.

## Required backend scenarios

| ID | Required assertion | Appropriate boundary |
| --- | --- | --- |
| B01 | Configuration defaults to authoritative reconciliation; required/unknown/removed keys fail clearly; the registered factory is discoverable. Explicit compatibility modes retain their documented boundary. | Config/factory and ServiceLoader tests. |
| B02 | Arbitrary configured IDs work, separate instances do not share ownership/state, and foreign backend IDs are rejected instead of ignored. | Compiler/backend tests; core routing is tested once centrally. |
| B03 | Validate the complete desired state before writes, including principal/identity writes. Missing, ambiguous, malformed or unsupported references fail with zero mutations. Preparation is read-only; apply can still fail after a valid preparation. | Pure compiler plus recording client/backend tests. |
| B04 | Create/update/delete/no-op and deterministic aggregation/normalization work. Reordering equivalent declarations or using equivalent references does not rewrite permissions. Repeat application directly through the plugin/synchronizer to establish idempotency. | Diff/synchronizer/backend tests. Core's equality shortcut is insufficient evidence. |
| B05 | Empty desired state/final removal revokes in-scope rights. Previous and desired targets retain cleanup scope; unrelated scopes survive. | Backend transaction tests and live cleanup assertions. |
| B06 | The default removes undeclared rights within the documented scope, including manually added rights. Each opt-in preservation mode preserves only its documented objects and rejects ownership collisions. | Synchronizer tests plus live native ACL/ownership checks. |
| B07 | Rollback converges to the previous agreed declaration, even when actual rights had drifted. It cleans both old and attempted scopes; it does not restore arbitrary external drift. | Backend transaction tests with a non-empty previous declaration and explicit drift. |
| B08 | Inject failure after an effective write, including a lost success response where relevant. Rollback and retry converge; errors remain checked backend failures. State irreversible account/password effects explicitly. | Stateful fake fault injection; real protocol/native transaction tests where a fake cannot establish semantics. |
| B09 | Client mapping, authentication, quoting/encoding, complete pagination, malformed responses and failed reads are tested. A partial catalog must never be treated as an empty or complete actual state. Test applicable timeout/retry behavior. | Local HTTP/HTTPS server or JDBC boundary, with live native semantics. |
| B10 | Passwords, API tokens/keys and Authorization values are masked in errors, nested causes, logs and durable state. Preserve useful non-secret details. Persistent resources are closed, including initialization failures; closing does not revoke access. | Synthetic reflected-secret failures that also assert diagnostic details survive, state/status checks, client/application lifecycle tests. |
| B11 | After failed rollback, apply the latest source declaration even when it equals the previous agreed state or is empty. Retain all attempted cleanup scopes through further failures; keep secrets out and clear uncertainty after success. | Core recovery tests plus scoped-plugin recovery tests. Runtime-only; restart persistence is not implemented. |
| B12 | External rename/move/deletion/recreation preserves native object identity and permits safe in-scope cleanup. Document name versus ID selectors, cascade behavior and repair triggers; exercise the applicable matrix below. | Stateful resolver/backend tests, with real-service checks for deletion, reused names, enforcement and restart. |

Every backend must cover B08 (an effective write followed by error/lost response,
compensation and retry) and B11 as applicable to its scope. Every catalog read
must cover B09 completeness: all pages/entries, later read failure, malformed or
inconsistent metadata, repeated IDs and premature termination. Unpaged SQL/SDK
catalogs need equivalent complete-read/failure tests, not artificial pagination.
Use [DIAGNOSTICS.md](DIAGNOSTICS.md) for required logging assertions.

Use observable state and mutation counts, not only successful return values.
Assert ordering only where correctness depends on it (for example, validation
before writes or identity creation before grants). Independent deletes need
not have a particular order. Do not require extra abstraction layers or tests
that merely restate Java set operations.

## External lifecycle matrix (B12)

| Scenario | Required assertion |
| --- | --- |
| Rename/move, stable native ID | ID selectors retain the object; name/parent selectors follow documented semantics without broadening scope. Current equivalent references are a no-op. |
| Missing desired target/principal | Fail before every write, including identity ensure, except explicitly supported principal creation. |
| Missing previous target or orphan actual permission | Native cascade is converged; revoke remaining physical grant IDs precisely in scope without resolving a missing display name. Preserve duplicates; report safe failure when scope/type cannot be established. |
| Reuse a name with a new ID; use an ID-looking name or repeat a name across parents/types/backends | Name selectors re-resolve on actual apply; old IDs never silently bind to replacements. Reject ambiguity and preserve typed identity and scope. |
| Catalog error/incompleteness | Zero writes; do not infer deletion from authorization/transport failures, filtered data or missing pages. |
| Change after prepare or during compensation | Preparation is read-only; apply rechecks. Compensation targets previous declarations and recoverable scope, and retry targets the latest source. |
| Equal declaration, then startup/source transition | Establish the real repair trigger. Core's equality shortcut remains after successful startup; fresh caches are not periodic drift detection. |

Apply this matrix to supported native objects/principals. Record non-applicable
cases and coverage gaps explicitly. See
[reference lifetime rules](BACKEND_DEVELOPMENT.md#external-deletion-recreation-and-reference-lifetime).

## Capability-dependent scenarios

| ID | When supported | Required assertions |
| --- | --- | --- |
| C01 | Names/IDs and catalogs | Equivalent name/ID inputs, ambiguity/missing references, parent/type/version preservation, every page, cache lifetime and alias conflicts before writes. |
| C02 | Identities | Identity-only state; ensure true/false; account creation before grants; generated passwords are creation-only; explicit Secret version rotation; no account deletion on grant removal; documented irreversible effects. Unsupported explicit identities fail with the checked not-implemented error. |
| C03 | Native permission branches | Every supported hierarchy/privilege family, wildcards, deny/masks/filters, routine signatures, default privileges and overrides as applicable. Confirm actual service behavior rather than only compiler output. |
| C04 | HTTPS/private CA | Correct CA succeeds; absent/wrong CA and hostname mismatch fail. TLS configuration is local to the client; cleanup releases its resources. |
| C05 | Enforcement | Use the granted principal to attempt allowed and denied operations before/after revocation and across scope boundaries. CRUD alone proves storage, not enforcement. Record server limitations as characterization tests. |

Non-applicable capabilities need a documented reason, not empty placeholder
tests. An unsupported request must not silently succeed.

## Shared coordination and provider tests

Core prepares all changed backends before applying any, records apply as
started before invoking it, and compensates only started transactions in reverse
order, including a partially failed one. A preparation failure never triggers
rollback. A failed transition does not advance agreed state, compensation
continues after a rollback failure, and the original failure remains available.

Core reapplies every participating backend on startup even when current and
restored declarations are equal. After a successful startup pass, equal backend
states are skipped even if the external system has drifted, unless compensation
previously failed. It does not periodically repair that drift. Only backends present in previous or desired
state or retained recovery scope participate; an unused configured
instance does not receive empty state on startup. This is separate from plugin
idempotency. A configuration-only mode change takes effect during startup
reconciliation, but does not force equal state to run later in the same process.

Startup repair and its limits are covered by
[PactCoreTest#reconcilesRestoredStateOnceAtStartupThenSkipsEqualDeclarations](pact-core/src/test/java/io/github/pactproject/core/PactCoreTest.java),
[PactCoreTest#startupReconcilesUnchangedChangedAndRemovedBackendsButNotUnusedOnes](pact-core/src/test/java/io/github/pactproject/core/PactCoreTest.java),
[PactCoreRecoveryTest#newCoreRepairsPartialWritesInRestoredScopeEvenWhenSourceWasReverted](pact-core/src/test/java/io/github/pactproject/core/PactCoreRecoveryTest.java), and
[PactCoreRecoveryTest#failedStartupIsRetriedEvenWhenCompensationSucceeded](pact-core/src/test/java/io/github/pactproject/core/PactCoreRecoveryTest.java).
These use a new core over retained fake external state; they do not establish
durable recovery of attempted scopes or hard-kill recovery of real services.
Kubernetes startup aggregation and fail-closed validation are covered by
[KubernetesStateProviderTest#startupAppliesCompleteCurrentStateInsteadOfMixingRestoredSnapshots](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/KubernetesStateProviderTest.java),
[KubernetesStateProviderTest#startupValidationFailureDoesNotApplyAnyPartialDesiredState](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/KubernetesStateProviderTest.java), and
[KubernetesStateProviderTest#startupApplyFailureKeepsLastSuccessfulStatusAndFinalizer](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/KubernetesStateProviderTest.java).

Finalizer opt-out is covered by
[KubernetesConfigTest#finalizersAreEnabledByDefaultAndCanBeDisabled](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/config/KubernetesConfigTest.java),
[KubernetesConfigTest#rejectsInvalidFinalizerSetting](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/config/KubernetesConfigTest.java),
[KubernetesStateProviderTest#disabledFinalizersStillReconcileStartupAndDeleteWithSharedAccess](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/KubernetesStateProviderTest.java),
[KubernetesStateProviderTest#disablingFinalizersReleasesExistingOnesBeforeFailedStartupCleanup](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/KubernetesStateProviderTest.java), and
[KubernetesStateProviderTest#disabledFinalizersRemoveExistingMarkerOnAddAndBeforeFailedDeleteCleanup](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/KubernetesStateProviderTest.java).
These use the mock API and a recording reconciler; they do not establish real
API deletion timing or external backend enforcement in opt-out mode.

Concrete assertions are in
[PactCoreTest#preparesChangedBackendsBeforeApplyingAny](pact-core/src/test/java/io/github/pactproject/core/PactCoreTest.java),
[PactCoreTest#doesNotRollBackPreparedTransactionsWhenPreparationFails](pact-core/src/test/java/io/github/pactproject/core/PactCoreTest.java),
[PactCoreTest#rollsBackOnlyStartedTransactionsInReverseOrderIncludingFailedApply](pact-core/src/test/java/io/github/pactproject/core/PactCoreTest.java),
[PactCoreTest#defaultRollbackReappliesPreviousStateAfterPartialWriteAndAllowsRetry](pact-core/src/test/java/io/github/pactproject/core/PactCoreTest.java),
[PactCoreTest#continuesCompensationAfterMultipleRollbackFailuresAndRetainsOriginalFailure](pact-core/src/test/java/io/github/pactproject/core/PactCoreTest.java),
[PactCoreTest#skipsUnchangedBackendEvenWhenExternalStateDrifts](pact-core/src/test/java/io/github/pactproject/core/PactCoreTest.java), and
[PactCoreTest#previousStateKeepsSecretVersionButNotPasswordDuringPreparationAndRollback](pact-core/src/test/java/io/github/pactproject/core/PactCoreTest.java).

Provider tests cover typed values, native target hierarchies, aggregation across
resources, shared access surviving one declaration's deletion, and malformed
input. Kubernetes additionally covers status/finalizer failure handling,
secret-free restart snapshots, exclusive identity ownership, Secret versions,
default ensure true, singular/list backend IDs and removal of one list member.
These are shared provider guarantees; do not copy them into every backend.

Artifact Keeper repository recreation and orphan cleanup are covered by
[ArtifactKeeperNameResolutionTest#rebindsRecreatedRepositoryBetweenPrepareAndApplyAndKeepsIdEquivalentGrant](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperNameResolutionTest.java),
[ArtifactKeeperNameResolutionTest#removesEveryOrphanGrantWithoutRewritingMatchingRecreatedRepositoryGrant](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperNameResolutionTest.java),
[ArtifactKeeperNameResolutionTest#missingDesiredRepositoryNameOrIdStillPreventsOrphanCleanupAndIdentityWrites](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperNameResolutionTest.java),
[ArtifactKeeperNameResolutionTest#compensationReappliesPreviousNamedGrantOnRecreatedRepositoryAfterPartialFailure](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperNameResolutionTest.java), and
[ArtifactKeeperNameResolutionTest#failedRepositoryCatalogReadNeverTurnsIntoOrphanCleanup](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperNameResolutionTest.java).
These fake-client scenarios verify permission CRUD planning and compensation;
server-side deletion/recreation and authorization enforcement are not exercised.

Additional B12 cases are
[ArtifactKeeperNameResolutionTest#repositoryRenameKeepsIdBoundGrantAndInvalidatesOldNameWithoutWrites](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperNameResolutionTest.java) and
[ArtifactKeeperNameResolutionTest#repositoryKeyEqualToOrphanUuidNeverAdoptsThatOrphanGrant](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperNameResolutionTest.java).
[ArtifactKeeperEnforcementLiveIntegrationTest#startupRebindsRecreatedRepositoryWithoutTransferringOldGrantAndRevocationStillEnforces](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperEnforcementLiveIntegrationTest.java)
verifies real repository deletion/recreation, a new ID, denied downloads before
repair, the equality shortcut, startup repair, name/ID no-op and revocation.
Fake tests independently force orphan/duplicate rows without relying on cascade.
[KubernetesLiveIntegrationTest#preservesGenericFieldsAndProcessesTheFullResourceLifecycle](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/KubernetesLiveIntegrationTest.java)
runs against the real API with finalizers enabled and disabled.
[KubernetesArtifactKeeperLiveIntegrationTest#optOutReleasesDeletingResourceBeforeFailedStartupCleanupOnRealApi](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/KubernetesArtifactKeeperLiveIntegrationTest.java)
verifies real deletion after opt-out with retained external grants after an
injected reconciler failure. It does not simulate a network outage.

B12 is a requirement, not a claim of complete existing coverage. Grafana has
missing/renamed/moved object cases; PostgreSQL, Ranger and Elasticsearch have
reference/recovery suites but not a complete external-object/principal
delete/recreate matrix. Their native selectors and scope remain module-specific.

Packaged plugin discovery and shared API class identity are exercised by
[PluginLoaderTest#discoversPackagedBackendWithSharedApiAndIndependentInstanceIds](pact-app/src/test/java/io/github/pactproject/app/plugin/PluginLoaderTest.java).
Its broken-descriptor case checks failure wrapping and JAR release; each
backend config suite also checks its real ServiceLoader registration.

All backend config parsers reject unknown keys. Their diagnostic string
representations preserve non-secret settings while masking connection passwords
and tokens. Elasticsearch additionally checks client release without grant/user
mutations in
[ElasticsearchBackendTest#closeReleasesClientWithoutChangingRolesOrUsers](pact-elasticsearch/src/test/java/io/github/pactproject/elasticsearch/ElasticsearchBackendTest.java).

## Backend evidence and remaining gaps

The following references are representative assertions, not exhaustive method
lists. The inventory below includes every current JUnit test class.

| Backend | Concrete evidence | Limits / remaining tests |
| --- | --- | --- |
| Ranger | [RangerPolicySyncTest#defaultScopeReconcilesEveryPolicyInTheConfiguredService](pact-ranger/src/test/java/io/github/pactproject/ranger/sync/RangerPolicySyncTest.java) covers B04/B06 and direct idempotency; its managed-only cases cover preservation/collisions. [RangerBackendTest#rangerRollbackRestoresDeclarationInsteadOfExternalPolicies](pact-ranger/src/test/java/io/github/pactproject/ranger/RangerBackendTest.java) covers source-state restoration. [RangerBackendTest#rollbackAndRetryConvergeAfterCreateSucceedsButResponseIsLost](pact-ranger/src/test/java/io/github/pactproject/ranger/RangerBackendTest.java) covers B08. [RangerHttpClientTest#readsEveryPolicyPageAndRejectsMalformedLaterPage](pact-ranger/src/test/java/io/github/pactproject/ranger/client/RangerHttpClientTest.java) covers paginated fail-closed reads. | Live HDFS/Ozone/Trino/Kafka scenarios validate policies against ServiceDefs/API, not authorization in those applications (C05). Ranger is responsible for target application enforcement; running those applications is not a mandatory PACT test requirement. Additional transport-timeout coverage remains useful; PACT tests its policy/catalog contract. |
| Artifact Keeper | [ArtifactKeeperBackendTest#preparationResolvesAllReferencesBeforeAnyBackendWrites](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperBackendTest.java) covers cross-backend preflight; its account/preflight case checks no ensure during preparation and changed references at apply. [ArtifactKeeperNameResolutionTest#compensationRestoresNamedArtifactGrantAfterPartialFailure](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperNameResolutionTest.java) covers named restoration. [ArtifactKeeperBackendTest#transactionsRetainOwnDeclarationsAndRollbackDoesNotRestoreManualDrift](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperBackendTest.java) covers separate transaction declarations and B07 despite a forward no-op. Resolver/diff suites cover logical vs physical grants and duplicate-row cleanup. Live pagination uses page size one. | Actual state is now local to each application, with a cache for newly ensured users only. Rate-limit exhaustion, interruption, shared request deadlines and full catalog failures have focused tests. Single-writer operation remains required. Artifact-only download denial in 1.11.1 is a server characterization, not proof of successful artifact-only enforcement. |
| PostgreSQL | [PostgreSqlBackendTest#rollbackRestoresDeclaredStateAndCleansAttemptedDatabaseInsteadOfSnapshot](pact-postgresql/src/test/java/io/github/pactproject/postgresql/PostgreSqlBackendTest.java) covers B07 and both scopes. [PostgreSqlBackendTest#compensatesCommittedDatabaseWhenLaterDatabaseFailsAndAllowsRetry](pact-postgresql/src/test/java/io/github/pactproject/postgresql/PostgreSqlBackendTest.java) covers recovery across databases with a stateful fake. [PostgreSqlLiveIntegrationTest#restoresPreviousDeclarationAfterRealDatabaseCommitAndInjectedLaterFailure](pact-postgresql/src/test/java/io/github/pactproject/postgresql/PostgreSqlLiveIntegrationTest.java) uses real JDBC commits and injects failure between databases. Other live cases cover authoritative ACL adoption, PUBLIC defaults, grantor mode, owner/column ACLs, routines/defaults, delegated grant authority, ignored grants and real login/authorization. | JDBC unit tests cover SQL generation/quoting, not PostgreSQL's native transaction/ACL semantics. Native privilege and commit checks require the opt-in live suite; the two-database fault is injected at the client boundary after a real commit. |
| Elasticsearch | [ElasticsearchRoleCompilerTest#rejectsForeignBackendIdInsteadOfIgnoringAccess](pact-elasticsearch/src/test/java/io/github/pactproject/elasticsearch/compile/ElasticsearchRoleCompilerTest.java) covers B02. [ElasticsearchBackendTest#rollbackRestoresDeclaredPrivilegesEvenIfApplyDidNotRewriteDriftedRole](pact-elasticsearch/src/test/java/io/github/pactproject/elasticsearch/ElasticsearchBackendTest.java) covers B07 despite a forward no-op; backend tests cover both modes, identities, reference races and write faults. Local TLS tests cover C04; live tests include real index/cluster authorization and lost user-write responses. | The filesystem Compose E2E exercises discovery, real authorization, privilege updates and empty-state cleanup. Expand failure/timeout cases as client behavior changes. |

Log readers are trusted as administrators of all configured backends. Principals,
resources, ACLs/policies, declarations, SQL and request/response details may be
logged for diagnosis. Mask passwords, API tokens/keys and Authorization values;
do not hide other backend data merely because it describes authorization.

Ranger and Artifact Keeper adapters have synthetic HTTP-error and invalid-JSON
reflection tests for B10. They retain sanitized server/parser details without
attaching raw causes that could reintroduce secrets. Ranger also tests a reflected
generated password; Elasticsearch tests both reflected API keys and a password
being set. The shared
[DiagnosticSanitizerTest](pact-api/src/test/java/io/github/pactproject/api/DiagnosticSanitizerTest.java)
checks known/raw/JSON-escaped values, named secrets and preservation of ACL data.
These checks do not prove all logging/status paths are secret-safe; extend them
when introducing new paths.

## Complete JUnit suite inventory

PostgreSQL ownership suites:

- [JdbcOwnershipTest](pact-postgresql/src/test/java/io/github/pactproject/postgresql/client/JdbcOwnershipTest.java): quoted DDL, read-only resolution, matching-owner no-op, missing/duplicate/malformed catalog references, canonical routine conflicts and sequence dependencies before writes.
- [PostgreSqlOwnershipLiveIntegrationTest](pact-postgresql/src/test/java/io/github/pactproject/postgresql/PostgreSqlOwnershipLiveIntegrationTest.java): opt-in PostgreSQL 18 ownership through filesystem and Kubernetes compilation/core, database/schema/table/view/materialized-view/partition/sequence/routine targets, native serial/identity dependencies, child boundaries, real owner DDL and SECURITY DEFINER execution, final-owner defaults, declared-owner compensation, lost commit responses, cross-database failure/retry and startup repair.

[PostgreSqlBackendTest#ownershipPreparationIsReadOnlyAndRollbackRestoresOnlyDeclaredOwners](pact-postgresql/src/test/java/io/github/pactproject/postgresql/PostgreSqlBackendTest.java)
checks read-only preparation and retained ownership on first-assignment compensation/removal.
[PostgreSqlOwnershipLiveIntegrationTest#bothProvidersAssignExactOwnersAndEmptyStateRetainsOwnership](pact-postgresql/src/test/java/io/github/pactproject/postgresql/PostgreSqlOwnershipLiveIntegrationTest.java)
covers both public declaration formats, matching-object catalog stability and native boundaries.
[PostgreSqlOwnershipLiveIntegrationTest#compensatesOwnerChangesAcrossDatabasesAfterFirstCommitAndAllowsRetry](pact-postgresql/src/test/java/io/github/pactproject/postgresql/PostgreSqlOwnershipLiveIntegrationTest.java)
injects failure after a real database commit. The startup scenario recreates core
over real remote state; it does not kill a JVM during reconciliation or recover
unknown original owners. Concurrent DDL and foreign-table ownership are not
established by these live cases. Existing owner roles may lack schema USAGE:
the defaults case verifies revocation does not retain temporary schema access.

Grafana plugin suites:

Named Grafana selectors follow the object currently at the declared path/title,
including previous-state cleanup after name reuse and startup restore. They do
not retain old UIDs across transitions. This contract is covered by
[GrafanaBackendTest#namedScopeFollowsCurrentFolderNameAcrossTransitionsAndRemoval](pact-grafana/src/test/java/io/github/pactproject/grafana/GrafanaBackendTest.java),
[GrafanaBackendTest#restoredNamedScopeCleansCurrentNameWithoutBindingOldUid](pact-grafana/src/test/java/io/github/pactproject/grafana/GrafanaBackendTest.java), and
[GrafanaBackendTest#missingPreviousNamesAllowRemovalWhileMissingDesiredNamesStillFail](pact-grafana/src/test/java/io/github/pactproject/grafana/GrafanaBackendTest.java).
Renamed/moved objects outside those selectors retain their ACLs; a missing
previous selector does not block cleanup, while ambiguity still fails closed.

- [GrafanaConfigTest](pact-grafana/src/test/java/io/github/pactproject/grafana/GrafanaConfigTest.java): authoritative defaults, strict config, discovery and credential masking.
- [GrafanaCompilerTest](pact-grafana/src/test/java/io/github/pactproject/grafana/GrafanaCompilerTest.java): native targets, aliases, permissions and deterministic compilation.
- [GrafanaBackendTest](pact-grafana/src/test/java/io/github/pactproject/grafana/GrafanaBackendTest.java): exact resolution, complete preflight, scope, compensation, recovery, native diagnostics and close.
- [GrafanaHttpClientTest](pact-grafana/src/test/java/io/github/pactproject/grafana/GrafanaHttpClientTest.java): wire mapping, all catalog boundaries, timeouts, encoding, indeterminate writes and secret-safe errors.
- [GrafanaProviderTest](pact-grafana/src/test/java/io/github/pactproject/grafana/GrafanaProviderTest.java): filesystem/Kubernetes declarations with arbitrary IDs and overlapping accesses.
- [GrafanaLiveIntegrationTest](pact-grafana/src/test/java/io/github/pactproject/grafana/GrafanaLiveIntegrationTest.java): opt-in native authorization, inheritance, cleanup and recovery.

[GrafanaBackendTest#compensatesLostResponsesToPreviousDeclarationAcrossAttemptedScopesAndRetries](pact-grafana/src/test/java/io/github/pactproject/grafana/GrafanaBackendTest.java)
covers B07/B08; its core recovery and separate-transaction cases cover B11.
The HTTP suite covers paginated search and unpaged user/ACL completeness,
and provider tests exercise both public declaration paths through core.
The live suite targets disposable Grafana 13.3.0: real dashboard read/edit
authorization, inherited folder access, unrelated boundary, cleanup and a
client-boundary fault after a successful real write. Fixture-created users
represent pre-existing OIDC users; OIDC login/linking is not tested. C02 is
rejected; C04 uses JVM trust only. Catalogs have no atomic revision/total, so
undetectable server-side omission/concurrent edits cannot be ruled out.

Additional recovery/catalog/diagnostic suites:

- [PactCoreRecoveryTest](pact-core/src/test/java/io/github/pactproject/core/PactCoreRecoveryTest.java): current-source recovery, retained scopes and secret-free history.
- [PactCoreDiagnosticsTest](pact-core/src/test/java/io/github/pactproject/core/PactCoreDiagnosticsTest.java): lifecycle correlation, truthful phase outcomes, timings and credential masking.
- [RateLimitPolicyTest](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/client/RateLimitPolicyTest.java): bounded server waits, date/reset formats, overflow, interruption and shared deadlines.
- [ArtifactKeeperRetryTest](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/client/ArtifactKeeperRetryTest.java): retry exhaustion/success, blocked-response timeout and no blind retry after an indeterminate write.
- [ArtifactKeeperCatalogTest](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/client/ArtifactKeeperCatalogTest.java): completeness of users/repositories/permissions/groups and unpaged projects, late failures and zero writes.
- [RangerCatalogTest](pact-ranger/src/test/java/io/github/pactproject/ranger/client/RangerCatalogTest.java): all policy envelopes, complete short pages with totals, late failures and zero writes.

The links are checked against the source tree. New, removed or renamed test
classes must update this inventory. Method references above are also checked.

| Module | Test classes and responsibility |
| --- | --- |
| API / Core | [DiagnosticSanitizerTest](pact-api/src/test/java/io/github/pactproject/api/DiagnosticSanitizerTest.java): safe detailed diagnostics. [PactCoreTest](pact-core/src/test/java/io/github/pactproject/core/PactCoreTest.java): routing, equality, startup restore, transaction ordering, compensation, secrets and shutdown. Shared state/transaction behavior is covered through core's API consumer tests. |
| Application | [PactConfigLoaderTest](pact-app/src/test/java/io/github/pactproject/app/config/PactConfigLoaderTest.java): YAML/environment; [PactConfigValidatorTest](pact-app/src/test/java/io/github/pactproject/app/config/PactConfigValidatorTest.java): plugin-instance config; [ReconciliationQueueTest](pact-app/src/test/java/io/github/pactproject/app/config/ReconciliationQueueTest.java): sequential execution/failure isolation; [PactComponentsFactoryTest](pact-app/src/test/java/io/github/pactproject/app/PactComponentsFactoryTest.java): initialization cleanup; [PluginLoaderTest](pact-app/src/test/java/io/github/pactproject/app/plugin/PluginLoaderTest.java): packaged discovery/shared API/broken descriptors. |
| Filesystem | [FileSystemStateProviderTest](pact-filesystem/src/test/java/filesystem/FileSystemStateProviderTest.java): typed YAML/accesses and errors. |
| Kubernetes | [ValueParserTest](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/compile/ValueParserTest.java): typed values/precision; [DataAccessCompilerTest](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/compile/DataAccessCompilerTest.java): generic/native models, expansion/identities; [KubernetesConfigTest](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/config/KubernetesConfigTest.java): config; [KubernetesStateProviderTest](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/KubernetesStateProviderTest.java): mock API lifecycle, aggregation, secrets/restart/status; [KubernetesLiveIntegrationTest](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/KubernetesLiveIntegrationTest.java): real API lifecycle with a recording reconciler; [KubernetesArtifactKeeperLiveIntegrationTest](pact-kubernetes/src/test/java/io/github/pactproject/kubernetes/KubernetesArtifactKeeperLiveIntegrationTest.java): real API/backend plus separate controller JVM restart. |
| Ranger | [RangerConfigTest](pact-ranger/src/test/java/io/github/pactproject/ranger/RangerConfigTest.java), [RangerPolicyCompilerTest](pact-ranger/src/test/java/io/github/pactproject/ranger/compile/RangerPolicyCompilerTest.java), [RangerPolicySyncTest](pact-ranger/src/test/java/io/github/pactproject/ranger/sync/RangerPolicySyncTest.java), [RangerBackendTest](pact-ranger/src/test/java/io/github/pactproject/ranger/RangerBackendTest.java), [RangerHttpClientTest](pact-ranger/src/test/java/io/github/pactproject/ranger/client/RangerHttpClientTest.java), [RangerLiveIntegrationTest](pact-ranger/src/test/java/io/github/pactproject/ranger/RangerLiveIntegrationTest.java). |
| Artifact Keeper | [ArtifactKeeperConfigTest](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperConfigTest.java), [ArtifactKeeperCompilerTest](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/compile/ArtifactKeeperCompilerTest.java), [ArtifactKeeperResolverTest](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/sync/ArtifactKeeperResolverTest.java), [ArtifactKeeperDiffTest](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/sync/ArtifactKeeperDiffTest.java), [ArtifactKeeperNameResolutionTest](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperNameResolutionTest.java), [ArtifactKeeperBackendTest](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperBackendTest.java), [ArtifactKeeperHttpClientTest](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/client/ArtifactKeeperHttpClientTest.java), [ArtifactKeeperLiveIntegrationTest](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperLiveIntegrationTest.java), [ArtifactKeeperEnforcementLiveIntegrationTest](pact-artifact-keeper/src/test/java/io/github/pactproject/artifactkeeper/ArtifactKeeperEnforcementLiveIntegrationTest.java). |
| PostgreSQL | [PostgreSqlConfigTest](pact-postgresql/src/test/java/io/github/pactproject/postgresql/PostgreSqlConfigTest.java), [GrantCompilerTest](pact-postgresql/src/test/java/io/github/pactproject/postgresql/compile/GrantCompilerTest.java), [GrantDiffTest](pact-postgresql/src/test/java/io/github/pactproject/postgresql/sync/GrantDiffTest.java), [PostgreSqlBackendTest](pact-postgresql/src/test/java/io/github/pactproject/postgresql/PostgreSqlBackendTest.java), [JdbcPostgreSqlClientTest](pact-postgresql/src/test/java/io/github/pactproject/postgresql/client/JdbcPostgreSqlClientTest.java), [PostgreSqlLiveIntegrationTest](pact-postgresql/src/test/java/io/github/pactproject/postgresql/PostgreSqlLiveIntegrationTest.java). |
| Elasticsearch | [ElasticsearchConfigTest](pact-elasticsearch/src/test/java/io/github/pactproject/elasticsearch/ElasticsearchConfigTest.java), [ElasticsearchRoleCompilerTest](pact-elasticsearch/src/test/java/io/github/pactproject/elasticsearch/compile/ElasticsearchRoleCompilerTest.java), [ElasticsearchBackendTest](pact-elasticsearch/src/test/java/io/github/pactproject/elasticsearch/ElasticsearchBackendTest.java), [ElasticsearchHttpClientTest](pact-elasticsearch/src/test/java/io/github/pactproject/elasticsearch/client/ElasticsearchHttpClientTest.java), [ElasticsearchTlsTest](pact-elasticsearch/src/test/java/io/github/pactproject/elasticsearch/client/ElasticsearchTlsTest.java), [ElasticsearchLiveIntegrationTest](pact-elasticsearch/src/test/java/io/github/pactproject/elasticsearch/ElasticsearchLiveIntegrationTest.java). |

## Other test entry points and execution

- [PactE2E.java](integration/e2e/PactE2E.java) and
  [run-e2e.sh](integration/scripts/run-e2e.sh) apply desired then empty state
  through the same application and inspect actual cleanup. Current filesystem
  E2E covers Ranger, Artifact Keeper, PostgreSQL and Elasticsearch. Elasticsearch
  asserts real read/write enforcement, a privilege update, another-index denial,
  revocation and generated-role removal; fixture user/index cleanup is separate.
- [check-helm.py](integration/scripts/check-helm.py) validates Helm renders,
  private-CA mounts, init containers and hardened filesystem settings. It is
  separate from Maven and needs Helm/PyYAML.
- [artifact-keeper-compatibility.yml](.github/workflows/artifact-keeper-compatibility.yml)
  and [elasticsearch-integration.yml](.github/workflows/elasticsearch-integration.yml)
  run focused live environments. They do not establish PostgreSQL/Ranger live
  results; the complete Compose runner has separate scenarios for those.
- [test-documentation.yml](.github/workflows/test-documentation.yml) checks the
  test inventory and file/method references on pull requests and master pushes.
- [filesystem-e2e.yml](.github/workflows/filesystem-e2e.yml) runs the application
  E2E for all four backends, including real Elasticsearch authorization checks.

Run with JDK 21 (preview is enabled in Maven):

```text
mvn -B -pl <changed-modules> -am test
mvn -B test
python integration/scripts/check-test-contract.py
```

The documentation checker checks file/method references and test-class inventory;
it cannot decide whether assertions actually satisfy a requirement. Review both
when changing semantics. Maven's default run skips opt-in live suites; local
HTTP/HTTPS unit tests still need loopback networking. Use the integration guide
for live commands and report the exact profile/service versions executed.

Keep tests at distinct boundaries: provider parsing, compiler, adapter, backend,
and enforcement are complementary. Mock restart and two-JVM restart are also
different guarantees. Keep these scenarios; parameterize repeated input shapes
where useful. Large PostgreSQL live fixtures can be extracted for readability
without removing native ACL scenarios.
