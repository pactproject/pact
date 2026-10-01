# PACT integration tests

Live integration tests are disabled by default. They connect to explicitly
configured external test services and should only run against isolated
resources:

- A dedicated Kubernetes namespace and a separate `DataAccess` CRD with no
  non-test objects in any namespace, because the provider watches that CRD
  cluster-wide. The CRD must preserve unknown fields in `spec.resources` and
  in `status.lastAppliedSpec`.
- A dedicated Ranger service with no existing policies labelled `managed`.
  PACT owns all managed policies for a configured Ranger service. The test
  creates or reuses the configured test user; that Ranger user is retained
  after the test.
- A dedicated Artifact Keeper instance with no existing permissions, plus a
  pre-created test user and repository. Artifact Keeper reconciliation lists
  and synchronizes permissions for the whole instance.

The Ranger test restores its initial managed-policy snapshot; the
Artifact Keeper test returns its dedicated instance to the initially empty
permission state. The Kubernetes test deletes its temporary custom resource.
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
| `PACT_IT_RANGER_TEST_USER` | Yes | Dedicated principal used in test policies. |
| `PACT_IT_ARTIFACT_KEEPER_URL` | Yes | Artifact Keeper API base URL. |
| `PACT_IT_ARTIFACT_KEEPER_TOKEN` | Yes | Artifact Keeper test token. |
| `PACT_IT_ARTIFACT_KEEPER_USER` | Yes | Existing user in the test instance. |
| `PACT_IT_ARTIFACT_KEEPER_REPOSITORY` | Yes | Existing repository in the test instance. |

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
mvn -pl pact-artifactkeeper -am -Dtest=ArtifactKeeperLiveIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dpact.integration=true test
```

To run the complete unit and live integration suite after all variables and
test services are ready:

```powershell
mvn -Dpact.integration=true test
```

The system property is required in addition to the environment configuration;
without `-Dpact.integration=true`, live tests are skipped and do not connect to
external services.
