# PACT integration tests

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
  the test removes only the permission it created.
- A disposable PostgreSQL cluster and a login with `CREATEDB` and `CREATEROLE`
  authority. The PostgreSQL live test creates a uniquely named temporary
  database and role, applies grants to those objects, and drops both afterward.
- A disposable Elasticsearch 9 cluster with Security API administration
  credentials. The live test creates a uniquely named native user with an
  ephemeral generated password and a deterministic PACT-owned role for a
  unique index pattern (it does not create an index). It removes only the
  role it owns and then deletes only that test's uniquely generated user.
  `docker compose down -v` removes the dedicated local Elasticsearch data
  volume.

The Ranger test restores its initial managed-policy snapshot; the
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
| `PACT_IT_POSTGRESQL_JDBC_URL` | Yes | JDBC URL to an administrative database on a disposable PostgreSQL cluster. |
| `PACT_IT_POSTGRESQL_USERNAME` | Yes | PostgreSQL account with `CREATEDB` and `CREATEROLE`. |
| `PACT_IT_POSTGRESQL_PASSWORD` | Yes | PostgreSQL test account password. |
| `PACT_IT_ELASTICSEARCH_ENDPOINT` | Yes | Elasticsearch 9 HTTP(S) endpoint. |
| `PACT_IT_ELASTICSEARCH_USERNAME` | Yes | Elasticsearch Security API administrator/test account. |
| `PACT_IT_ELASTICSEARCH_PASSWORD` | Yes | Elasticsearch test account password. |

Kubernetes connectivity uses Fabric8's normal client configuration (for
example, the current kubeconfig or in-cluster credentials). Because the
provider lists and watches the CRD cluster-wide, the test identity needs
cluster-wide `get`, `list`, `watch`, and `patch` access to the custom resource,
plus `patch` access to its `/status` subresource.

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
Artifact Keeper, and PostgreSQL tests. Its test user name and index pattern
are generated uniquely for each run; the test cleans up only that user's
owned PACT role and then the user itself.

For Artifact Keeper 1.5.1, Compose sets the server's `ADMIN_PASSWORD` setting.
The bootstrap job reads the one-time admin password from the storage volume
when upgrading an existing test volume, then changes it to the configured
test admin password before creating fixtures.

The bootstrap creates the dedicated Ranger HDFS service and Artifact Keeper
test user/repositories. Ranger's test image also provides Ozone, Trino, and
Kafka service definitions and preconfigured `dev_ozone`, `dev_trino`, and
`dev_kafka` services; PACT writes only `managed` policies to them during these
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
reconcile grants to Ranger, Artifact Keeper, and PostgreSQL, then reconcile an
empty state to clean them up:

```powershell
docker compose --env-file .\integration\.env -f .\integration\compose.yaml `
  --profile e2e run --rm pact-e2e
```

The end-to-end command returns a failure if either apply or cleanup fails.
The PostgreSQL Maven live test runs as part of the `tests` profile and creates
its own temporary database and identity on the Compose PostgreSQL service.
