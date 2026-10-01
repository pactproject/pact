# pact-kubernetes

`pact-kubernetes` supplies the managed Kubernetes state provider for PACT. It
observes namespace-scoped `DataAccess` custom resources cluster-wide, compiles
their desired permissions, and invokes the PACT reconciler serially. Unlike
snapshot providers such as the filesystem provider, this provider keeps the
application running until shutdown.

## Configuration

Select `kubernetes` as the state provider type. The optional provider
configuration keys are `group`, `version`, and `plural`; their defaults are
`com.example.ru`, `v1`, and `dataaccesses`. They can also be set with
`DATA_ACCESS_GROUP`, `DATA_ACCESS_VERSION`, and `DATA_ACCESS_PLURAL`
environment variables. Explicit provider configuration takes precedence over
the environment.

`KubernetesConfig` owns resource coordinate parsing and defaults. The
`controller` package contains informer, queue, status, and finalizer behavior;
the `compile` package translates DataAccess specs into PACT state.

The Fabric8 client uses its normal Kubernetes configuration, including the
in-cluster service account when running in Kubernetes. The configured resource
is expected to be namespace-scoped and to expose the `status` subresource.

The Artifact Keeper backend configured in PACT must have id `registry`, which
is the service name used by `DataAccess.spec.resources`. Registry locations
with `name` are translated to Artifact Keeper repository targets, and
`permissions` are translated to backend `actions`.

## Controller behavior

- Startup lists all `DataAccess` resources and rebuilds applied state only from
  `status.lastAppliedSpec`; it then starts the informer and waits for its cache
  to sync before processing events.
- Add and changed-generation update events are processed in FIFO order. Updates
  that do not change `metadata.generation` are ignored. A deletion timestamp
  takes priority over generation checks; status-only updates produced while
  cleanup is in progress do not enqueue repeated cleanup passes.
- Each event computes the cluster-wide desired state from the last successful
  snapshot of every resource. An event only replaces its own
  `namespace/name` snapshot after the core reports successful reconciliation.
- Status is written through the status subresource. Failed validation or
  backend reconciliation sets `phase: Error` and `observedGeneration` while
  preserving the last successful `lastAppliedSpec`.
- The controller adds `<group>/data-access` before applying a resource.
  During deletion, it removes that finalizer only after successful cleanup.
- Shutdown stops accepting events, lets queued reconciliation work finish,
  stops the informer, and closes the Kubernetes client.

Required cluster-wide RBAC:

```yaml
rules:
  - apiGroups: ["com.example.ru"]
    resources: ["dataaccesses"]
    verbs: ["get", "list", "watch", "patch"]
  - apiGroups: ["com.example.ru"]
    resources: ["dataaccesses/status"]
    verbs: ["patch"]
```

Replace the API group above when configuring a different group. The controller
does not create or modify the CRD.

## Current scope

Registry resources for Artifact Keeper are wired end to end. Ozone and Trino
resources require a Ranger backend, which is not yet included; their use will
fail reconciliation if no matching backend is installed. The CRD schema,
Ranger policy compilation, and deployment manifests are separate follow-up
work.
