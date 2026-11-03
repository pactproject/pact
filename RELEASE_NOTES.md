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
