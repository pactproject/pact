# PACT 10

## Changes

- Kubernetes finalizers can be disabled with provider config
  `finalizers-enabled: "false"`. The default remains enabled. Opt-out removes
  existing PACT finalizers, including deleting resources, before backend cleanup;
  other controllers' finalizers are preserved.
- Artifact Keeper compares repository grants using native target IDs. Grants
  referencing deleted repositories participate in authoritative cleanup by
  permission ID instead of blocking the whole backend on a missing name.
  Named declarations resolve to recreated repositories' current IDs on apply.
  Missing/ambiguous desired references and failed catalogs still prevent writes.
- Added external lifecycle regressions for rename, ID-looking names, duplicate
  orphan rows, recreation between prepare/apply and compensation. Live tests
  verify private repository downloads before/after recreation/startup repair and
  real Kubernetes finalizer opt-out, including failed startup cleanup.
  The repository-recreation/delete/recreate DataAccess chain is tested in both
  finalizer modes, including startup cleanup and overlapping declarations.
- Shared backend contract B12 defines reference lifetime, typed identity,
  orphan cleanup, catalog failures, compensation and repair triggers. Lifecycle
  regressions now cover all backend native models, with concrete remaining
  limits recorded in TESTING.md.
- PostgreSQL validates desired grant/default/identity references before identity
  writes and rechecks them on apply. A removed previous database no longer blocks
  remaining-database cleanup; only exact native database-not-found is skipped.
- Ranger rechecks the service type and ServiceDef before apply/compensation and
  ensures declared users even when policy content already matches. A removed
  user can be restored on actual apply without a policy rewrite.
- Artifact Keeper plugin is 1.0.1, Kubernetes plugin is 1.0.2, and Helm chart
  1.1.2 defaults to image `ghcr.io/pactproject/pact:10`. PostgreSQL is 1.0.3 and
  Ranger is 1.0.1; the other plugin versions remain unchanged.

## Upgrade from 9

Rebuild/use the bundled plugins with PACT API 10. Declaration formats and
authoritative scopes remain unchanged. Artifact Keeper can now remove orphan
repository grants in its existing instance-wide scope, so review the complete
desired declaration before reconciliation. Correct references to repositories
that are still missing; restarting cannot make an invalid desired name valid.

External recreation alone does not trigger reconciliation. Startup reapplies
current declarations; after successful startup, equal declarations remain
skipped. This release does not introduce periodic drift repair.

Keep finalizers enabled to retain deletion cleanup guarantees. If explicitly
opting out, restart the controller to remove existing markers. Deletion then
uses best-effort cleanup; a backend failure, missed event or offline controller
can leave external grants after the DataAccess/status has disappeared.
Re-enabling finalizers cannot reconstruct deleted snapshots. To confirm
revocation, reconcile an empty access declaration successfully before deletion.

---

# PACT 9

## Changes

- The new Grafana backend reconciles existing users' direct folder/dashboard
  ACLs in one organization, through both filesystem and Kubernetes declarations.
  It supports exact names and UIDs, authoritative replacement within declared
  object scope, preflight validation and previous-declaration compensation.
  Users must already exist; account registration/OIDC linking and data-source
  authorization remain outside its scope. Native tests target Grafana 13.3.0.
- Startup reconciliation reapplies the current declaration to every backend in
  current or restored state, including equal declarations. Kubernetes validates
  and applies the complete aggregate of active specs before informer events.
  Configured backends absent from both states remain untouched. After a successful
  startup pass, unchanged declarations are skipped as before.
- PostgreSQL supports explicit `ownership: [database, schema, table, sequence,
  function, procedure]` constraints. The access principal becomes the owner of
  the selected concrete levels. Ownership requires authoritative mode and a
  superuser connection. Existing declarations without ownership retain their
  ownership behavior.
- PostgreSQL ownership changes precede creator-derived defaults and ACL
  reconciliation. Canonical reference/owner conflicts and dependent sequence
  constraints fail before identity writes. Owner ACL cleanup also works when
  the recorded grantor lacks schema USAGE, without retaining extra schema access.
- Helm chart 1.1.1 defaults to image `ghcr.io/pactproject/pact:9`.

## Upgrade from 8

Review the complete desired declaration before restarting. Startup now performs
real reconciliation even when YAML matches restored status: authoritative
backends can revoke manually added, undeclared permissions within their scope.
Changes to backend configuration also take effect during that startup pass.

Use plugins rebuilt against PACT API 9; the image bundles its matching plugins
and dependencies. Plugin module versions remain independent of the application
release number. The shared plugin transaction API has not changed.

For PostgreSQL, no ownership constraint means no owner changes. Removing the
last ownership declaration leaves the current owner in place. There is no
fallback owner or recursive adoption of database/schema children. PostgreSQL's
native table-owned serial/identity sequences still follow their table owner.
An ownership-only declaration still participates in the usual authoritative
database ACL scope; include every required grant in desired state.

Compensation restores only explicitly declared previous owners. A committed
first owner assignment can survive compensation when previous ownership was
unspecified. Original-owner snapshots are not stored.

## Recovery limits

Startup reconciles the current source; it does not resume interrupted rollback.
Repair is limited to scopes recoverable from current/restored declarations and
backend configuration. Attempted scopes known only to a lost process and unknown
original owners cannot be reconstructed. Kubernetes restores successful specs
from status; filesystem applied history remains process-local.
