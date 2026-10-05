# PACT Ranger backend

`pact-ranger` reconciles a PACT backend state with policy state in one Apache
Ranger service. It loads the configured Ranger ServiceDef for each prepared
transaction and uses its resource names, parent relationships,
recursive-resource support, access types, and optional data-mask/row-filter
definitions when compiling PACT `Access` objects. Compilation has no
service-type-specific Ozone or Trino branches.

The backend id identifies the service block used in PACT state (and in
`DataAccess.spec.resources`); it should match the Ranger ServiceDef type
returned for the configured Ranger service. Configure one backend per Ranger
service instance. By default, PACT treats every policy in the configured
service as part of its authoritative state: policies absent from desired state
are deleted, and a same-name policy may be updated to match PACT's desired
policy. This is intentionally a service-wide, destructive scope; use a
dedicated Ranger service for PACT or explicitly accept that PACT owns all its
policies.

```yaml
backends:
  - id: ozone
    type: ranger
    config:
      base-url: http://ranger.example/service/
      username: pact
      password: "<secret>"
      service-name: ozone-cluster
  - id: trino
    type: ranger
    config:
      base-url: http://ranger.example/service/
      username: pact
      password: "<secret>"
      service-name: trino-cluster
      page-size: "100"
```

Required settings are `base-url`, `username`, `password`, and `service-name`;
`page-size` defaults to 100. The base URL is the Ranger `/service/` API root.
Credentials use HTTP Basic authentication. At reconciliation time, PACT loads
the named Ranger service, reads its ServiceDef type from that service, and
fetches that ServiceDef. `service-type` is not configured separately.

### Preserving non-PACT policies

Set `managed-only: "true"` to opt into reconciling only policies carrying the
`managed` label. PACT-generated policies carry this label. Policies without it
are left unchanged and are not deleted; this is not the default because PACT's
configured desired state is intended to be authoritative.

```yaml
  - id: ozone
    type: ranger
    config:
      base-url: http://ranger.example/service/
      username: pact
      password: "<secret>"
      service-name: ozone-cluster
      managed-only: "true"
```

In this mode, if a non-managed policy already has the deterministic name of a
desired PACT policy, PACT cannot adopt or delete that policy and Ranger may
reject the create as a duplicate. Resolve the collision explicitly (for
example, rename or remove the external policy) before retrying. The option
does not reduce the permission needed to read service policies; it only
narrows which policies PACT changes.

## Access mapping

Resource target keys are matched to ServiceDef resource `name` values. A
permission map is keyed by those same names, for example
`permissions: { table: [select] }`. PACT compiles a separate policy at every
non-empty permission level and includes the resource's ServiceDef parent chain.
Access values are checked against the ServiceDef access types and any
resource-specific access type restrictions. `denyPermissions` compiles into
Ranger deny policy items.

`conditions` become Ranger `_expression` conditions and each
`validitySchedule` interval becomes a Ranger schedule in `Europe/Moscow`.
`override` maps to `isOverride`. `dataMask` and `rowFilter` are accepted only
when the loaded ServiceDef defines `dataMaskDef` or `rowFilterDef`. Their
required target fields, access types, and supported mask types come from those
definitions; the legacy mask values (`mask`, `showLast4`, `hash`, `custom`,
etc.) are mapped to Ranger's mask type names. They compile to policy types 1
and 2. Different transformations with the same policy identity share one
policy and remain separate policy items. Recursive resource flags use the
target convention `is<ResourceName>Recursive`, for example `isKeyRecursive`,
and are checked against `recursiveSupported`.

The Ranger backend/compiler and Kubernetes DataAccess compiler do not have an
Ozone/Trino/Kafka service allow-list. A new Ranger service can be used by
configuring a backend whose id matches the service block name in
`spec.resources`, and setting the name of an existing Ranger service whose
type matches that id. Resource names, parents, and permissions are resolved
through its ServiceDef. The special `registry` service block remains reserved
for Artifact Keeper.

The CRD access shape stays the legacy shape: `users`, `permissions` and
`denyPermissions` are retained, with permission maps keyed by Ranger resource
names. Other Ranger service types can provide their own location map and
permission map without adding service-specific code. `dataMask` and
`rowFilter` are still top-level access fields as in the legacy contract, and
are accepted only when the Ranger ServiceDef provides the corresponding
definitions; no service name is hard-coded in the Ranger backend.

The ServiceDef is authoritative for supported Ranger access types. This is
broader than the legacy Ozone permission enum: current Apache Ranger's Ozone
ServiceDef also exposes `read_acl`, `write_acl`, and `assume_role`. The generic
DataAccess compiler intentionally does not impose those old service-specific
enums; values are checked against the configured service's ServiceDef.

Policy names are deterministic, and state changes use a prepared snapshot so a
failed multi-backend reconciliation can compensate Ranger changes. With the
default mode, the snapshot and reconciliation include every policy returned for
the configured service. With `managed-only: "true"`, only policies with the
`managed` label are included.

Before creating or updating desired policies, PACT ensures every user referenced
by their allow, deny, data-mask, or row-filter items exists in Ranger. It uses
Ranger's user-name lookup endpoint and creates a missing Ranger user with a
cryptographically random password, active status, and visibility enabled,
matching legacy behavior. The password is not persisted or logged; these users
are only principals in Ranger policies and do not need to authenticate to
Ranger.

The Ranger service account needs permission to read the configured service,
ServiceDefs, policies, and Ranger users, and to create users and policies and
to update/delete policies for the configured service.

## Integration tests

With `-Dpact.integration=true`, `RangerLiveIntegrationTest` exercises HDFS,
Ozone, Trino, and Kafka ServiceDefs through a live Ranger API. The Ozone test
creates a key policy, the Trino test creates a table policy, and the Kafka test
creates a topic policy; each verifies service type, resource hierarchy,
principal, access types, managed label, update, idempotence, and cleanup. These
tests do not require the Ozone, Trino, or Kafka applications to be running.
They require dedicated Ranger services with no pre-existing policies labelled
`managed`; non-managed policies are preserved. See
[`INTEGRATION_TESTS.md`](../INTEGRATION_TESTS.md) for configuration and the
local Compose setup.
