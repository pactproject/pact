# PACT backend development guide

This guide describes how to design, implement, review, and operate a PACT
backend plugin. It is intended for PACT operators, maintainers, and coding
agents. A backend changes authorization in an external system: its ownership
boundary and failure behavior must be designed before implementation.

## Current architecture

PACT's extension point is `pact-api`:

- `BackendFactory` identifies a plugin type and creates a backend instance from
  its configured `id` and string-valued configuration.
- `Backend` receives a `PactState`. Each access identifies a principal,
  backend id, target, and backend-interpreted attributes.
- `BackendTransaction` represents an apply/compensation pair.
- `PactCore` groups state by backend id, skips unchanged backends, prepares
  transactions for changed backends, then applies them in deterministic backend
  id order.

The current `Backend.prepare(previous, desired)` default implementation calls
`apply(desired)` and compensates by calling `apply(previous)`. This is suitable
only when `apply` is a complete, idempotent reconciliation of the backend's
owned state. A backend that uses snapshots, performs side effects, or cannot
reconstruct its owned previous state should override `prepare`.

Core prepares all changed backend transactions before applying any. If any
apply fails, it calls `rollback` on every prepared transaction in reverse
order, including transactions whose apply may not have run yet. Therefore,
preparation must not mutate the external system, and rollback must be safe
even if apply was never called or only partially completed.

PACT's current backend modules do not yet follow one uniform internal layout:

| Backend | Existing structure | Transaction pattern |
| --- | --- | --- |
| Ranger | `compile`, `sync`, `api`, `client`, config and backend | By default reconciles every policy in the configured service; `managed-only` opts into limiting writes/deletes to policies labeled `managed`. Ensures missing policy users, creating them with random passwords that are not retained. |
| PostgreSQL | `compile`, `model`, `sync`, `api`, `client`, config and backend | Snapshots PACT-granted ACLs; reconciles identities separately; grant rollback does not reverse password changes. |
| Artifact Keeper | `sync`, `model`, `api`, `client`, config and backend | Treats permissions returned by the configured service as the actual state and deletes permissions absent from desired state. Backend owns most orchestration and mutable working state; uses default `prepare` compensation. Missing `svc-` usernames trigger service-account creation. |
| Elasticsearch | README and Maven scaffold | Not implemented yet; use the recommendations in this guide rather than copying a partial implementation. |

Artifact Keeper's current implementation already reflects the authoritative
scope policy: it loads the permissions returned by `getPermissions()`, diffs
the whole set against PACT's desired permissions, and deletes entries that are
absent. Its effective scope is therefore the permission collection returned
for the configured Artifact Keeper instance. The resolver currently rejects
actual permission entries outside user-on-repository, so such entries can make
reconciliation fail before changes are applied.

There is a common external plugin contract, but not yet a common internal
implementation template. New backends should use the layered pattern below.
Existing modules can be aligned incrementally, when there is a concrete
correctness or maintainability benefit; do not do a broad refactor just to make
directory names identical.

## Common baseline and backend-specific decisions

The shared PACT baseline is that desired state is authoritative within the
explicitly configured backend scope. By default, an object in that scope which
is absent from desired state is removed, even if it was originally created
outside PACT. Preserving unmanaged objects is an opt-in behavior, not the
implicit default. The scope must be explicit and visible in configuration and documentation; the
default does not authorize changes outside it.

The following properties should be consistent across backends:

| Shared baseline | Backend-specific decisions |
| --- | --- |
| Authoritative reconciliation and deletion of stale in-scope objects by default; any preservation mode is explicit opt-in. | How the external scope is selected and whether it is a service, database set, repository set, namespace, or other boundary. |
| Validate the complete desired model before writes; reject unsupported input rather than silently ignore it. | Native resource hierarchy, permission names, inheritance, wildcards, default privileges, deny semantics, and aggregation. |
| Idempotent retries; explicit behavior after partial failure; no claim of atomicity where the service cannot provide it. | Whether a reliable snapshot exists, how compensation works, and which operations are irreversible. |
| No secret or credential leakage through logs, errors, status, or durable snapshots; least-privilege backend credentials. | Principal types, ensure/create behavior, password support, group membership, and cleanup constraints. |
| Document configuration defaults, scope, destructive effects, external version support, and required service permissions. | API pagination, consistency, concurrency controls, quotas, name limits, and version-specific capabilities. |

For each backend, answer the design questions below before adding code. Record
the answers in that backend's README and tests; don't silently import another
backend's behavior.

1. **What is the configured scope?** Clarify whether reconciliation covers
   one database, service, account, repository, cluster, or the whole backend.
   Identify how an operator narrows it. State what an empty desired set and
   deletion of the final declaration do. Default reconciliation deletes all
   stale objects in this scope.
2. **Is there a preserve-unmanaged opt-in?** State how it identifies managed
   objects and what happens on name collisions.
3. **What does a principal mean?** Decide whether principals must pre-exist,
   can be ensured, or are created as a side effect. Specify whether PACT
   manages credentials and whether that action is reversible. Keep credentials
   used by PACT to connect to the service separate from target principals'
   credentials.
4. **What is the permission model?** Specify hierarchy, inheritance, wildcard
   expansion, deny behavior, default privileges, and interactions with access
   granted outside PACT. Reject unsupported or ambiguous inputs explicitly.
5. **What can fail partway through?** List remote operations and their ordering.
   Decide what is atomic, what can be compensated, and what must be retried as
   an irreversible side effect.
6. **How does the service behave under concurrent writers?** Identify races,
   conditional update support, pagination, eventual consistency, rate limits,
   and server-side limits. Document any single-writer requirement.
7. **What authorization does PACT itself need?** Request the least privilege
   that supports reads, writes, and cleanup. Document it alongside
   configuration.

Ownership markers (for example labels or grantor identities) are useful for
opt-in preservation and for reliable diffs, but the default still treats the
configured scope as the source of truth. Explain destructive consequences
prominently and test both default full-scope behavior and any
preserve-unmanaged opt-in.

## Recommended module structure

Follow the repository's Java/Maven plugin conventions and use only the layers
needed by the backend:

```text
pact-example/
  src/main/java/io/github/pactproject/example/
    ExampleBackend.java
    ExampleBackendFactory.java
    ExampleConfig.java
    api/       # narrow client interface and client exceptions
    client/    # HTTP/JDBC/protocol implementation
    compile/   # pure validation and PactState -> desired model conversion
    model/     # immutable backend-domain values
    sync/      # diff, ownership selection, and synchronization
  src/main/resources/META-INF/services/
    io.github.pactproject.api.BackendFactory
  src/test/java/...
  README.md
```

Use `compile`, `model`, `api`, `client`, and `sync` consistently where they
help clarify responsibility. Do not add empty wrappers or mirror this tree
mechanically. The backend class should coordinate the layers, not also contain
HTTP parsing, SQL construction, permission validation, and a bespoke diff.

Keep the compiler deterministic and side-effect free. It should validate the
backend id, target shape, supported attributes and privilege names before
remote mutations begin. Represent validated desired state with immutable
domain types rather than passing raw YAML/Jackson maps throughout the plugin.

Keep the client focused on service protocol operations and translate protocol
failures into a backend-specific checked exception. Keep reconciliation logic
in a synchronizer that works against explicit actual and desired state. Keep
configuration parsing in a small config type that validates required values
at plugin creation.

## Reconciliation and transaction pattern

For a backend with a reliable snapshot API, the recommended flow is:

```text
prepare(previous, desired):
    validate and compile desired
    read actual state for the configured scope and ownership mode
    calculate a deterministic plan
    return transaction(snapshot, plan)

transaction.apply():
    apply the plan; surface partial failure

transaction.rollback():
    reconcile PACT-owned state back to the snapshot
```

Preparation should not perform mutations. A transaction should capture
immutable snapshots and plans, not depend on mutable fields shared between
calls. Apply and rollback should be idempotent or safe to retry after partial
failure. In the default authoritative mode, reconcile and restore the full
configured scope. In a preserve-unmanaged opt-in mode, filter both apply and
rollback consistently so unmanaged objects remain untouched.

Not every service can provide atomic transactions. If compensation is
impossible, state that explicitly and design for convergence: expose useful
errors, keep enough applied-state information to retry, and avoid claiming
atomicity. Do not pretend an irreversible user creation or password change can
be rolled back. Make the operation order and retry behavior part of the
backend contract and documentation.

Use `Backend.prepare`'s default only when applying a complete previous state
is a correct compensation. If the backend itself already takes snapshots,
overrides `prepare`, or has irreversible side effects, encode and test those
semantics explicitly. In particular, do not hide cross-backend transaction
assumptions inside backend-local mutable state.

## Plugin wiring

For a new backend:

1. Add its Maven module to the root aggregator and configure its dependency on
   `pact-api`.
2. Implement `BackendFactory.type()` and `create(id, config)`. Validate
   required configuration there; do not create connections until needed unless
   startup validation requires it.
3. Register the factory in
   `src/main/resources/META-INF/services/io.github.pactproject.api.BackendFactory`
   with its fully qualified class name.
4. Keep configuration keys and example YAML in the module README.
5. Keep the backend id (the instance selected by state) distinct from the
   plugin type (the implementation selected by configuration). Support
   multiple instances of the same type without hard-coded backend ids.

Use the existing Ranger or PostgreSQL factory and ServiceLoader registration
as working examples.

## Testing requirements

Tests should prove behavior and ownership, not just that the compiler returns
an object:

- **Compiler/model tests:** valid inputs, every supported privilege, unknown
  fields and privileges, malformed targets, hierarchy and wildcard behavior,
  deterministic aggregation, and backend-id mismatch.
- **Diff/synchronizer tests:** create, update, delete, no-op, empty desired
  state, full-scope deletion by default, opt-in preservation of unmanaged
  objects, duplicate actual objects/name collisions, normalization, and stable
  ordering where order matters.
- **Backend transaction tests:** snapshot isolation, apply, rollback after
  successful and partial apply, rollback when apply has not run, retry, and
  non-compensatable side effects.
- **Client tests:** request/response mapping, pagination, authentication,
  timeouts, API errors, malformed responses, safe SQL/URL/identifier handling,
  and confirmation that sensitive values are not logged.
- **Plugin tests:** config validation, factory creation, and successful
  ServiceLoader discovery.
- **Live integration tests:** opt-in tests against a disposable isolated
  service. Prove actual permission outcomes and ownership preservation, not
  just API response shapes. Never run destructive tests against shared
  production-like principals or resources.

Use fakes for deterministic unit tests, but do not rely on a fake to establish
that the real service's transaction, privilege inheritance, API defaults, or
deletion semantics behave as expected. Keep live tests isolated and opt-in;
follow the repository's `INTEGRATION_TESTS.md` guidance.

## Coding-agent instructions

An AI coding agent can help implement a backend, but it must not decide
authorization semantics by guessing from API names or examples. Give it:

- the written ownership and permission contract;
- the exact scope and empty-state/deletion behavior;
- allowed service versions, APIs, and privileges;
- required transaction and irreversible-side-effect behavior;
- security and least-privilege requirements;
- the expected tests and any external integration environment.

Ask the agent to inspect existing modules first, state which backend pattern
it will follow, then implement a minimal end-to-end vertical slice. Require it
to identify assumptions, external API uncertainties, all destructive
operations, and any behavior it cannot test. Do not ask it to invent policy
semantics or expand scope silently.

Treat agent output as a proposed patch, not an approval. A human maintainer
must review the contract and diff before merging and must own the production
integration test.

## Human review checklist

The maintainer responsible for the backend should verify:

- default desired state mutates exactly the documented configured scope, and
  removing declarations deletes stale state there as specified;
- destructive default and any preserve-unmanaged opt-in are prominent in the
  module README, config validation, and tests; nothing outside the configured
  scope can be changed;
- unknown and malformed input fails before mutation;
- permission inheritance, wildcards, default grants, and principal existence
  match the real service semantics;
- partial failure, rollback, retry, and concurrent-writer behavior are
  accurate and tested;
- secrets, tokens, SQL, HTTP bodies, and credential-bearing URLs cannot leak
  through logs, exceptions, status, or snapshots;
- config and service-account permissions are least-privilege and documented;
- tests prove full-scope deletion by default and unmanaged-state preservation
  only when the opt-in is enabled, and run against the real service in an
  isolated environment where semantics cannot be established by unit tests
  alone;
- all code paths, including cleanup and restart recovery, are covered and the
  final diff contains no unrelated changes.

For authorization-changing code, AI review, unit tests, and a successful build
are useful evidence, but they do not replace an accountable human review of
ownership, destructive behavior, and real-service semantics.

## Incremental alignment of existing backends

Ranger and PostgreSQL already have most of the layered structure above.
Preserve their service-specific permission semantics and align ownership
defaults incrementally. Ranger's `managed-only` setting opts into preserving
policies without the `managed` label; by default all policies in the configured
service are reconciled. PostgreSQL currently discovers PACT-issued ACL entries
by grantor and scopes by databases in previous or desired state; assess that
mechanism against the authoritative-scope baseline before changing it.

Artifact Keeper is a candidate for a later structural alignment: move
orchestration away from the mutable backend working-state field, make its
configured service-wide permission scope and destructive default explicit,
and add a prepared transaction with tested compensation. Preserve-unmanaged
mode, if offered, needs a supported way to filter that state; do not confuse a
generic API `target_type` string with enforcement support.

Build Elasticsearch against the documented contract from its first
implementation. Do not copy the Artifact Keeper backend's legacy orchestration
or assume that its behavior is the PACT-wide standard.
