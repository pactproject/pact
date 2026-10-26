# Backend diagnostic contract

Log readers are trusted as administrators of every configured backend. Names,
IDs, principals, declarations, ACLs/policies, native permission details and SQL
may be logged. Passwords, API tokens/keys and Authorization values must be masked,
including new/generated passwords, JSON-escaped reflections and nested errors.
Do not attach a raw transport/SDK/parser exception that bypasses masking.

## Required events and levels

| Boundary | Level | Required information |
| --- | --- | --- |
| Reconciliation start/end | INFO; ERROR on failure | `reconciliation` ID, `phase=reconcile`, `result`, desired access/identity counts at start, `duration_ms` at end, recovery backend IDs on failure. |
| Changed backend prepare/apply/rollback | INFO; ERROR on failure | Same reconciliation ID, configured `backend` ID, `phase`, `result=started/completed/failed`, elapsed monotonic `duration_ms` on completion/failure, exception type on failure. |
| Ordinary unchanged backend | DEBUG | Reconciliation ID, backend ID, `phase=skip`, `result=unchanged`. A backend with failed-compensation context must not be skipped. |
| Plan and scope | DEBUG, or a compact INFO summary | Native scope, previous/desired object counts, retained recovery scope and planned create/update/delete counts where known. |
| Native mutation | DEBUG | Action, principal/object and native scope/ID, start and result. Include privilege sets, safe role/policy definitions or permission SQL when useful. Identity actions report role/user and action only, never password-bearing SQL/bodies. |
| Failure | WARN/ERROR | Failed phase/operation, safe native error details, HTTP status or SQLState where available; confirmed completed operations if tracked. Preserve the original apply failure when compensation also fails. |

The core provides the common phase events for every backend run through PACT.
Plugin synchronizers/adapters provide native action detail. SQL adapter events
can identify database/role while their enclosing backend/core events identify
the configured backend instance. Direct standalone client calls do not have a
core reconciliation ID.

`completed` means that the invoked operation returned successfully. It is not a
claim of authorization enforcement or an atomic cross-service commit. Distinguish
planned actions, confirmed successful API calls, executed SQL and committed
database transactions. A lost response can hide a successful remote write; never
count it as a confirmed failure to mutate. Do not fabricate counts that a plugin
does not track, and do not make extra external reads solely to fill log fields.

Examples:

```text
reconciliation=<id> backend=search phase=prepare result=completed duration_ms=12
backend=search action=assign_roles result=started principal=alice roles=[readers]
reconciliation=<id> backend=search phase=apply result=failed duration_ms=40 error_type=BackendOperationException
reconciliation=<id> backend=search phase=rollback result=completed duration_ms=15
```

## Validation and tests

- Capture lifecycle logs and assert correlation, backend IDs, phases, outcomes
  and durations. Failed apply must not emit an apply-completed event.
- Reflect configured credentials and a password being set in synthetic server
  errors. Assert both that credentials are absent and useful user/resource/error
  details remain. Include nested causes and diagnostic configuration strings.
- Keep identity password values out of retained state/recovery declarations.
- Log level changes must not change external reads, mutation ordering or scope.
- Use DEBUG for complete object definitions; do not flood INFO with every
  catalog object. Routine no-op/idempotent behavior should remain distinguishable
  from a failed operation or lost response.

See [TESTING.md](TESTING.md) for concrete tests and the backend test contract.
