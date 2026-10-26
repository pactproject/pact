# PACT Helm chart

This chart deploys PACT as a single-replica Kubernetes controller. It renders
the PACT application configuration, creates the controller ServiceAccount and
cluster-wide `DataAccess` RBAC, and can install the namespaced `DataAccess`
CRD. It does not create backend services or backend credentials.

## Install

Build and push the PACT image, then set `image.repository` and `image.tag` to
the image available to the cluster:

```powershell
helm upgrade --install pact .\charts\pact `
  --namespace pact-system `
  --create-namespace `
  --set image.repository=registry.example/pact `
  --set image.tag=8 `
  --values .\pact-values.yaml
```

The chart's default image is `ghcr.io/pactproject/pact:8`. Set a repository
and version explicitly for the features required by your deployment.

The chart installs a `DataAccess` CRD by default. Set `crd.create: false` when
the CRD is managed separately. The CRD group, version, plural, and singular are
configurable under `dataAccess`; the generated controller configuration uses
the same group/version/plural values.

The CRD is rendered as a chart template so the create flag and API coordinates
can be configured. It is annotated with Helm's `resource-policy: keep`: Helm
will retain it on uninstall or when `crd.create` is later disabled. It also
means Helm does not remove it for the operator. Review CRD schema changes and
upgrade them deliberately; deleting a CRD deletes its custom resources.
Changing the group, version, or plural changes the CRD identity and needs an
explicit migration plan; the prior CRD is retained.

The chart gives the controller cluster-wide access to the configured
`DataAccess` resource (`get`, `list`, `watch`, `patch`) and its status
subresource (`patch`). This is separate from Secret access.

## PACT configuration

The chart creates a ConfigMap from `pact.stateProvider.config` and
`pact.backends`. For example:

```yaml
pact:
  stateProvider:
    config:
      informer-resync-millis: "5000"
      informer-start-timeout-seconds: "30"
      status-retry-delays-millis: "100,200,400"
  backends:
    - id: analytics-db
      type: postgresql
      config:
        jdbc-url: jdbc:postgresql://postgres.example:5432/postgres
        username: pact
        password: ${PG_PASSWORD}

extraEnv:
  - name: PG_PASSWORD
    valueFrom:
      secretKeyRef:
        name: postgres-credentials
        key: password
```

PACT resolves `${ENVIRONMENT_VARIABLE}` references in string values after
parsing the YAML configuration. Keep passwords out of Helm values and the
generated ConfigMap; inject them from Kubernetes Secrets using `extraEnv`.
Unset references cause configuration loading to fail. Environment substitution
is a PACT config-file feature and applies to all string values.

## Backend CA certificates and init containers

Use `extraVolumes` and `extraVolumeMounts` to mount an existing ConfigMap or
Secret containing a CA bundle. Elasticsearch's `ca-certificate` points to the
mounted PEM file; it configures only that backend client and preserves
hostname verification. A complete values example is
[`examples/elasticsearch-ca.yaml`](examples/elasticsearch-ca.yaml):

```yaml
extraVolumes:
  - name: elasticsearch-ca
    configMap:
      name: elasticsearch-ca
extraVolumeMounts:
  - name: elasticsearch-ca
    mountPath: /etc/pact-ca/elasticsearch
    readOnly: true
```

Set the backend's `ca-certificate: /etc/pact-ca/elasticsearch/ca.crt` and create
the ConfigMap with a `ca.crt` key. CA rotation is picked up when the controller
restarts; changing a referenced ConfigMap alone does not recreate clients.

`extraInitContainers` accepts ordinary Kubernetes container definitions. It can
prepare a shared JVM truststore on an `emptyDir` mounted with `extraVolumes`;
mount the result into PACT and set `JAVA_TOOL_OPTIONS` via `extraEnv` with the
appropriate `javax.net.ssl.trustStore` arguments. Configure volume permissions
and init-container security settings for UID/GID 10001. This changes JVM-wide
trust and is useful for clients using default JVM TLS configuration. The
per-backend PEM option needs no init container or truststore password.

## Secret access for identities

The controller does not receive `list` or `watch` permissions on Secrets.
Secret reads are disabled in the chart by default. To grant a particular
controller permission to read named Secret objects, configure:

```yaml
secretAccess:
  create: true
  grants:
    - namespace: payments
      names:
        - app-credentials
    - namespace: reporting
      names:
        - warehouse-credentials
```

For each grant, Helm creates a namespaced Role with only `get` on the listed
Secret names and a RoleBinding to the chart's ServiceAccount. This is designed
to be managed by ArgoCD alongside SealedSecrets. RBAC scopes access to the
whole Secret object, not to a particular key; prefer one credential per
Secret. Use the same namespace for the `DataAccess` and its referenced Secret.

If PACT must read Secrets outside the namespaces and names listed above, the
broader cluster-wide option is:

```yaml
secretAccess:
  readAll: true
```

This creates a separate ClusterRole and ClusterRoleBinding granting only
`get` on Secrets in every namespace. It does not grant `list` or `watch`, so
PACT can read a Secret by a known name but cannot enumerate Secrets. Prefer
namespace/name-scoped grants whenever possible; `readAll` is disabled by
default. Only trusted GitOps administrators should be allowed to change the
RBAC that grants PACT access to Secrets.

## Single replica and operations

The chart runs one replica and does not expose a replica-count setting. The
controller serializes events in a process-local queue and does not coordinate
work across replicas. The Deployment uses a `Recreate` strategy to avoid
overlapping old and new controllers during an update. The application has no
HTTP health endpoint, so the chart does not configure liveness/readiness
probes.

The pod runs as UID/GID 10001, drops Linux capabilities, disables privilege
escalation, and uses a read-only root filesystem with a writable `/tmp`.
Configure resource requests/limits and image pull secrets with the standard
chart values.

## Values

See `values.yaml` for the complete set of chart values. `rbac.create: false`
and `serviceAccount.create: false` allow using externally managed Kubernetes
RBAC and ServiceAccounts. `secretAccess.grants` creates one Role and
RoleBinding per namespace entry; keep namespaces unique in the list.
`secretAccess.readAll` creates a separate cluster-wide Secret reader role and
binding, including when the chart's main `rbac.create` setting is false.
