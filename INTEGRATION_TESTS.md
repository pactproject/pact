# PACT integration tests

The shared requirements, test inventory and coverage limits are in
[TESTING.md](TESTING.md). A default Maven pass skips the opt-in live suites;
report executed service/profile versions separately from source coverage.

Live integration tests are disabled by default. They connect to explicitly
configured external test services and should only run against isolated
resources:

- A dedicated Kubernetes namespace and a separate `DataAccess` CRD with no
  non-test objects in any namespace, because the provider watches that CRD
  cluster-wide. The CRD must preserve unknown fields in `spec.resources` and
  in `status.lastAppliedSpec`.
- Dedicated Ranger services for HDFS, Ozone, Trino, and Kafka. The integration
  tests opt into `managed-only: true`, snapshot and restore policies labelled
  `managed`, and leave other policies untouched. By default, PACT treats every
  policy in the configured Ranger service as desired-state scope and may
  delete or update policies without that label. The tests create or reuse the
  configured test user; that Ranger user is retained after the test. No Ozone,
  Trino, or Kafka application is required: tests validate Ranger policy
  creation against the live ServiceDefs and policy API.
- A dedicated Artifact Keeper instance with no existing permissions, plus a
  pre-created test user and at least two repositories. Artifact Keeper
  reconciliation lists and synchronizes permissions for the whole instance.
  The service-account test leaves its uniquely named service account in place;
  the test removes its permission, revokes its test token, and deletes the
  uniquely named private repository it creates to verify effective access.
- A disposable PostgreSQL cluster and a login with `CREATEDB` and `CREATEROLE`
  authority. The PostgreSQL live test creates a uniquely named temporary
  database and role, applies grants to those objects, and drops both afterward.
  The cross-database recovery scenario creates two temporary databases and
  injects failure at the client boundary after a real JDBC commit, then checks
  restoration of the previous declaration and successful retry.
- A disposable Elasticsearch 9.2.3 cluster with Security API administration
  credentials. Live tests create unique native users, custom roles and indices
  with test documents. They verify actual read/write and cluster authorization,
  both reconciliation modes, existing/built-in role assignments, account
  passwords, validation before writes and partial-failure compensation. Cleanup
  removes only their uniquely generated users, roles and indices.
  `docker compose down -v` removes the dedicated local Elasticsearch data
  volume.

The Ranger test compensates through its previous declared managed-policy states; the
Artifact Keeper test returns its dedicated instance to the initially empty
permission state. Its service-account integration test retains the generated
account because Artifact Keeper does not expose user deletion through this
backend. The Kubernetes test deletes its temporary custom resource.
Do not point these tests at production or shared instances. Credentials are
read from environment variables and are not written to the repository.

## Configuration

Set these variables in the test process:

| Variable | Required | Purpose |
| --- | --- | --- |
| `PACT_IT_K8S_NAMESPACE` | Yes | Dedicated namespace where the temporary DataAccess object is created. |
| `PACT_IT_K8S_GROUP` | Yes | API group of the separately installed generic DataAccess CRD. |
| `PACT_IT_K8S_VERSION` | No | CRD version; defaults to `v1`. |
| `PACT_IT_K8S_PLURAL` | No | CRD plural; defaults to `dataaccesses`. |
| `PACT_IT_RANGER_BASE_URL` | Yes | Ranger `/service` API base URL. |
| `PACT_IT_RANGER_USERNAME` | Yes | Ranger test account. |
| `PACT_IT_RANGER_PASSWORD` | Yes | Ranger test account password. |
| `PACT_IT_RANGER_SERVICE_NAME` | Yes | Dedicated, otherwise-unused Ranger service. |
| `PACT_IT_RANGER_RESOURCE` | Yes | Resource name from that service's live ServiceDef. |
| `PACT_IT_RANGER_OZONE_SERVICE_NAME` | Yes | Dedicated Ranger service whose ServiceDef type is `ozone`. |
| `PACT_IT_RANGER_TRINO_SERVICE_NAME` | Yes | Dedicated Ranger service whose ServiceDef type is `trino`. |
| `PACT_IT_RANGER_KAFKA_SERVICE_NAME` | Yes | Dedicated Ranger service whose ServiceDef type is `kafka`. |
| `PACT_IT_RANGER_TEST_USER` | Yes | Dedicated principal used in test policies. |
| `PACT_IT_ARTIFACT_KEEPER_URL` | Yes | Artifact Keeper API base URL. |
| `PACT_IT_ARTIFACT_KEEPER_TOKEN` | Yes | Artifact Keeper test token. |
| `PACT_IT_ARTIFACT_KEEPER_USER` | Yes | Existing user in the test instance. |
| `PACT_IT_ARTIFACT_KEEPER_REPOSITORY` | Yes | Existing repository in the test instance. |
| `PACT_IT_ARTIFACT_KEEPER_API_PROFILE` | No | `current` (default), tested with 1.11.1; `legacy` fails at initialization. |
| `PACT_IT_POSTGRESQL_JDBC_URL` | Yes | JDBC URL to an administrative database on a disposable PostgreSQL cluster. |
| `PACT_IT_POSTGRESQL_USERNAME` | Yes | PostgreSQL account with `CREATEDB` and `CREATEROLE`. |
| `PACT_IT_POSTGRESQL_PASSWORD` | Yes | PostgreSQL test account password. |
| `PACT_IT_ELASTICSEARCH_ENDPOINT` | Yes | Elasticsearch 9 HTTP(S) endpoint. |
| `PACT_IT_ELASTICSEARCH_USERNAME` | Yes | Elasticsearch Security API administrator/test account. |
| `PACT_IT_ELASTICSEARCH_PASSWORD` | Yes | Elasticsearch test account password. |
| `PACT_IT_ELASTICSEARCH_CA_CERTIFICATE` | No | PEM CA bundle path for private HTTPS test servers; enables the real private-CA rejection/acceptance test. |

Kubernetes connectivity uses Fabric8's normal client configuration (for
example, the current kubeconfig or in-cluster credentials). Because the
provider lists and watches the CRD cluster-wide, the test identity needs
cluster-wide `get`, `list`, `watch`, and `patch` access to the custom resource,
plus `patch` access to its `/status` subresource.

## Focused Elasticsearch 9.2.3 with TLS

This standalone Compose stack creates a private CA and a server certificate
with localhost and service-name SANs. It uses a separate project/volumes and
port 19201 by default:

```powershell
docker compose -p pact-es-923 -f integration/compose.elasticsearch.yaml up -d --wait --wait-timeout 240
New-Item -ItemType Directory -Force target | Out-Null
docker cp pact-es-923-elasticsearch-certificates-1:/certificates/ca/ca.crt target/pact-es-ca.crt
$env:PACT_IT_ELASTICSEARCH_ENDPOINT = 'https://localhost:19201'
$env:PACT_IT_ELASTICSEARCH_USERNAME = 'elastic'
$env:PACT_IT_ELASTICSEARCH_PASSWORD = 'PactLocalElastic2!'
$env:PACT_IT_ELASTICSEARCH_CA_CERTIFICATE = (Resolve-Path target/pact-es-ca.crt).Path
mvn -B -pl pact-elasticsearch -am -Dpact.integration=true test
docker compose -p pact-es-923 -f integration/compose.elasticsearch.yaml down -v
```

The checked-in password is only for this disposable stack. Override
`PACT_IT_ELASTICSEARCH_PASSWORD` before starting it if needed. The CA private
key and server key stay in its disposable certificate volume. Linux CI follows
the same sequence in `.github/workflows/elasticsearch-integration.yml`, also
running the full unit suite and checking Helm defaults, CA mounts and init
containers. The existing full multi-backend Compose stack uses plain HTTP for
Elasticsearch 9.2.3; use this focused stack for live private TLS coverage.

## Running

On PowerShell, set the required `$env:PACT_IT_*` variables, then run one module:

```powershell
mvn -pl pact-kubernetes -am -Dtest=KubernetesLiveIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dpact.integration=true test
mvn -pl pact-ranger -am -Dtest=RangerLiveIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dpact.integration=true test
mvn -pl pact-artifact-keeper -am -Dtest=ArtifactKeeperLiveIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dpact.integration=true test
mvn -pl pact-postgresql -am -Dtest=PostgreSqlLiveIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dpact.integration=true test
mvn -pl pact-elasticsearch -am -Dtest=ElasticsearchLiveIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dpact.integration=true test
```

To run the complete unit and live integration suite after all variables and
test services are ready:

```powershell
mvn -Dpact.integration=true test
```

The system property is required in addition to the environment configuration;
without `-Dpact.integration=true`, live tests are skipped and do not connect to
external services.

## Local Docker Compose stack

`integration/compose.yaml` starts local instances of Apache Ranger, Artifact
Keeper, their required databases/search service, a separate PostgreSQL target
for PACT, a security-enabled Elasticsearch 9 target, and the test runner.
Ranger uses PostgreSQL for its database, and Artifact Keeper requires its own
OpenSearch service. Elasticsearch uses a dedicated disposable volume and
local-only administrator password.

Copy `integration/.env.example` to `integration/.env` and adjust the local-only
credentials if needed. Start the backend services and their one-time bootstrap
jobs first:

```powershell
docker compose --env-file .\integration\.env -f .\integration\compose.yaml `
  up -d --build
```

The Ranger image uses `RANGER_DB_PASSWORD` for its PostgreSQL credentials and
initial `admin` account; the example matches the password expected by its
bundled development-service bootstrap.

Once they are ready, run the backend Maven live tests:

```powershell
docker compose --env-file .\integration\.env -f .\integration\compose.yaml `
  --profile tests run --rm backend-live-tests
```

This runner executes the Elasticsearch live test alongside the Ranger,
Artifact Keeper, and PostgreSQL tests. Elasticsearch test users, custom roles
and indices are generated uniquely for each scenario and removed afterward;
built-in roles and other users' assignments are not changed.

Artifact Keeper CI tests the current API contract against 1.11.1 in its own
fresh disposable database. It tests service-account creation, private repository
access, idempotency, and revocation, plus permission CRUD for repository,
artifact, project, group, system, global, and an unfamiliar target type. CIDR
round trips and group/anonymous principal checks are always included.
These generic CRUD tests do not assert artifact-level content enforcement.
`ArtifactKeeperEnforcementLiveIntegrationTest` adds current-profile download
checks: two independent TCP peers exercise permitted/denied CIDRs, alternating
requests detect IP-insensitive authorization caching, and forged forwarding
headers must not bypass the restriction. Named project grants allow content in
two assigned repositories while denying an unrelated project's content;
revocation denies subsequent downloads. A separate 1.11.1 characterization test
confirms that an artifact-only grant does not pass the repository download gate.
It must not be interpreted as successful per-artifact authorization.
The rollback scenario forwards real HTTP calls and injects one 503 only after a
successful permission deletion. It checks that `PactCore` recreates the original
grant including CIDRs and can retry the desired transition afterward.

The enforcement suite requires `current`. Its CIDR scenario additionally needs
`PACT_IT_ARTIFACT_KEEPER_ALLOWED_PROBE_URL`,
`PACT_IT_ARTIFACT_KEEPER_DENIED_PROBE_URL`, and
`PACT_IT_ARTIFACT_KEEPER_ALLOWED_PROBE_HOST` (the allowed proxy's IP or a DNS name
resolvable by the test runner). Compose and compatibility CI configure these
automatically. Without the allowed-probe URL, that one scenario is skipped;
the other current-profile enforcement checks still run. The probe proxies use
real TCP addresses; do not configure them as trusted forwarding proxies in AK.
Name-resolution scenarios create temporary groups, repositories/artifacts and
projects. They verify exact name-to-ID mapping, repository scope, revision
selectors, and no grant rewrite when switching a named declaration to equivalent
IDs. Artifact Keeper 1.5.1 is no longer supported; `api-profile: legacy` fails
at backend initialization before any request. Image 1.0.6 is the last compatible
release for that server version.
The Compose defaults and `integration/.env.example` use:

```dotenv
ARTIFACT_KEEPER_VERSION=1.11.1
PACT_IT_ARTIFACT_KEEPER_API_PROFILE=current
```

Use separate Compose project names (`-p`) for different server versions so their
databases and storage volumes are isolated. Do not downgrade an upgraded database.
The profile is passed to the Maven runner and both E2E apply/cleanup configurations.

Compose sets the server's `ADMIN_PASSWORD` setting.
The bootstrap job reads the one-time admin password from the storage volume
when upgrading an existing test volume, then changes it to the configured
test admin password before creating fixtures.

The bootstrap creates the dedicated Ranger HDFS service and Artifact Keeper
test user/repositories. Ranger's test image also provides Ozone, Trino, and
Kafka service definitions; bootstrap explicitly ensures their `dev_ozone`,
`dev_trino` and `dev_kafka` test services, including on fresh databases.
PACT writes only `managed` policies to them during these
tests. No corresponding backend applications are started. The bootstrap
rejects an Artifact Keeper instance with existing permissions. The tests
restore their managed state; if a failed test leaves data behind, reset the
disposable local stack with:

```powershell
docker compose --env-file .\integration\.env -f .\integration\compose.yaml down -v
```

`down -v` deletes all local test databases and stored data. Do not use this
Compose project with production or shared backends.

To run the PACT end-to-end path through the `filesystem` state provider,
reconcile grants to Ranger, Artifact Keeper, PostgreSQL and Elasticsearch, then reconcile an
empty state to clean them up:

```powershell
docker compose --env-file .\integration\.env -f .\integration\compose.yaml `
  --profile e2e run --rm pact-e2e
```

The end-to-end command applies desired and empty state through the same
`PactApplication` instance, retaining the core's previous state so cleanup
actually reconciles removals. It returns a failure if apply or cleanup fails,
or if repository permissions, managed Ranger policies, or explicit PostgreSQL
fixture grants remain afterward. Two independent one-shot processes cannot
test this transition because the filesystem provider does not persist applied
state across process restarts.
The Elasticsearch fixture creates a unique native user and two indices outside
the declaration. It verifies denial before apply, read-only access in the
declared index, denied access in the other index, a declarative update allowing
writes, and denial after empty-state cleanup. The test also verifies removal of
generated roles/assignments while the user and documents still exist, then
deletes its fixture user/indices. Fixture markers in `e2e/desired-state.yaml`
are expanded by `PactE2E.java` before the filesystem provider reads the file;
this does not add identity or environment-variable parsing to that provider.
The `filesystem-e2e.yml` workflow runs the same Compose path against all four
backends on relevant pull requests and master pushes.
The PostgreSQL Maven live test runs as part of the `tests` profile and creates
its own temporary database and identity on the Compose PostgreSQL service.

## Kubernetes to Artifact Keeper E2E and process restart

The `kubernetes` profile starts a disposable, privileged k3s container. It does
not use or modify Docker Desktop's Kubernetes cluster or the user's kubeconfig.
Run it with Artifact Keeper 1.11.1 / `current` selected as above:

```powershell
docker compose --env-file integration/.env -p pact-k8s-it -f integration/compose.yaml `
  --profile kubernetes build artifact-keeper-bootstrap
docker compose --env-file integration/.env -p pact-k8s-it -f integration/compose.yaml `
  --profile kubernetes run --rm kubernetes-artifact-keeper-tests
docker compose --env-file integration/.env -p pact-k8s-it -f integration/compose.yaml `
  --profile kubernetes down -v
```

`KubernetesArtifactKeeperLiveIntegrationTest` sends public DataAccess YAML
through a real Kubernetes API, watch controller, core, and Artifact Keeper.
The fixture uses arbitrary backend ID `packages`, mixed human and `svc-*`
subjects, named repositories/projects/groups, a qualified artifact path, and
flat `allowed_cidrs`. It checks persisted status, exact target/principal UUIDs,
wire restrictions, an API update, and finalizer-driven deletion of all grants.
The second scenario stops the first controller JVM, starts a new JVM, verifies
restoration of all five accesses from `status.lastAppliedSpec` without changing
physical permission IDs, then deletes the resource and verifies real cleanup.
This is provider persistence; filesystem applied state remains process-local.

The tests create and remove only their dedicated
`integration.pactproject.github.io` CRD and namespace when absent, and require
that CRD to contain no existing resources cluster-wide. The generated k3s admin
kubeconfig is shared only with the disposable runner; the runner removes its
temporary copy on exit. `down -v` removes the cluster and credentials volume.
The CI workflow runs these E2Es separately from the Artifact Keeper live-test job.
Set `PACT_INTEGRATION_TEST_IMAGE` to select a separately tagged local runner
image; the default is `pact-integration-tests:local`.
