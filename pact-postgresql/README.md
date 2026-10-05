# PACT PostgreSQL backend

`pact-postgresql` provides a plugin backend for PostgreSQL privileges over
JDBC at database, schema, table, column, sequence, function and procedure
levels.
Row-level security and identity/password provisioning remain out of scope.

Role/identity provisioning is a separate design concern described below; it is
not part of the grant compiler.

## Access contract

Like Trino and Ozone, the resource `target` is a path through the object
hierarchy and the keys of `permissions` are levels of that path:

| Level | Target field | Privileges |
| --- | --- | --- |
| `database` | `database` | `CONNECT`, `CREATE`, `TEMPORARY` (`TEMP`) |
| `schema` | `schema` | `USAGE`, `CREATE` |
| `table` | `table` | `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, `REFERENCES`, `TRIGGER` |
| `column` | `column` | `SELECT`, `INSERT`, `UPDATE`, `REFERENCES` |
| `sequence` | `sequence` | `USAGE`, `SELECT`, `UPDATE` |
| `function` | `function` | `EXECUTE` |
| `procedure` | `procedure` | `EXECUTE` |

- `database` is required; each deeper field requires its parent
  (`table`, `sequence`, `function` and `procedure` need `schema`;
  `column` needs `table`).
- `sequence` is a sibling branch of `table` under `schema`. A resource can
  name both branches, for example a table and its associated sequence; the
  sequence is identified by its schema and sequence name. PostgreSQL's
  `OWNED BY` dependency on a table column is not required or inferred.
- `function` is also a child of `schema`. Its value must include the exact
  argument types, including empty parentheses for a zero-argument function;
  PostgreSQL overloads therefore remain unambiguous. Write argument types as
  PostgreSQL SQL types, schema-qualifying custom types when needed, e.g.
  `add(integer, integer)` or `convert(public.source_type)`. PACT resolves the
  signature against the database catalog before changing grants.
- `procedure` is another child of `schema`. It uses the same exact-signature
  syntax as `function` and the same `EXECUTE` privilege, but targets a
  PostgreSQL procedure.
- A `permissions` key must be a level present in the target. Each key grants
  its privileges along the ancestor path to that level. A single access can
  therefore carry permissions on the `table → column`, `sequence`,
  `function` and `procedure` branches.
- `schema`, `table`, `column` and `sequence` accept `*`, meaning every
  existing object at that level (system schemas `pg_*` and
  `information_schema` are excluded; tables include views, materialized
  views, partitioned and foreign tables). `database` cannot be `*`.
- A privilege on the wrong level (for example `SELECT` on `database`) fails
  validation.

Read everything in a database:

```yaml
resources:
  - analytics-db:
      database: analytics
      schema: "*"
      table: "*"
      sequence: "*"
    access:
      - users:
          - alice
        permissions:
          database:
            - CONNECT
          schema:
            - USAGE
          table:
            - SELECT
          sequence:
            - USAGE
```

Read-write on one table and one column:

```yaml
resources:
  - analytics-db:
      database: analytics
      schema: sales
      table: orders
      column: email
    access:
      - users:
          - bob
        permissions:
          table:
            - SELECT
          column:
            - UPDATE
```

Grant execute on one exact function overload:

```yaml
resources:
  - analytics-db:
      database: analytics
      schema: sales
      table: orders
      function: calculate_discount(numeric, text)
    access:
      - users:
          - app
        permissions:
          table:
            - SELECT
          function:
            - EXECUTE
```

Grant execute on one exact procedure overload:

```yaml
resources:
  - analytics-db:
      database: analytics
      schema: sales
      procedure: refresh_orders(date)
    access:
      - users:
          - app
        permissions:
          procedure:
            - EXECUTE
```

`*` is expanded when PACT reconciles, so tables, columns and sequences created
later are not covered until the next reconciliation of that resource.
PostgreSQL's `ALTER DEFAULT PRIVILEGES` is not managed. Column-level and
table-level grants are independent; a table-level privilege already covers
all columns.

## Configuration

Configure a PostgreSQL backend per cluster/connection. The backend `id` is
the service block name in `DataAccess.spec.resources`; `database` names the
database receiving the grant:

```yaml
backends:
  - id: analytics-db
    type: postgresql
    config:
      jdbc-url: jdbc:postgresql://postgres.example:5432/postgres
      username: pact_grant_manager
      password: <injected-from-a-secret>

stateProvider:
  type: kubernetes
  config: {}

# Example DataAccess spec:
resources:
  - analytics-db:
      database: analytics
    access:
      - users:
          - alice
        permissions:
          database:
            - CONNECT
            - TEMPORARY
```

The PostgreSQL backend factory is registered with Java `ServiceLoader`.
Settings `jdbc-url`, `username`, and `password` are required. The JDBC URL
identifies the cluster; PACT replaces its database part with each managed
database, because object-level ACLs live in per-database catalogs. The
grantor therefore needs `CONNECT` on every managed database. Object names are
passed to `GRANT`/`REVOKE` as quoted SQL identifiers.
Inject credentials using the deployment's secret mechanism; do not commit
real passwords to configuration. The plugin runtime needs pgJDBC
(`org.postgresql:postgresql`) visible in the plugin class loader.

Rules:

- `Access.principal` is the name of an existing PostgreSQL role.
- `Resource.backendId` selects the configured PostgreSQL connection.
- `WITH GRANT OPTION`, `PUBLIC`, role membership, object ownership,
  default privileges and identity provisioning are out of scope.
- Privilege names are case-insensitive; unknown privilege or target names
  fail validation rather than being interpolated into SQL.
- An access with no permissions expresses no grants for that role/target;
  previously PACT-managed grants in scope are revoked.

## Grant ownership contract

The PostgreSQL login configured for this backend is the PACT grantor. Its
database ACL entries (the PostgreSQL ACL `grantor`) are the ownership boundary:
the backend reads only grants issued by `current_user`, never manages `PUBLIC`
or grants issued by another role, and reconciles PACT's direct database grants
to the desired set. The grantor identity should be dedicated to PACT and must
not be used for manual grants or shared with another independently
reconciling PACT backend for overlapping databases. Otherwise those grants
cannot be distinguished from PACT-owned state.

The grantor must already have sufficient authority to grant/revoke the
configured privileges and connect to every managed database. The
backend does not create roles, databases, or grant options. If it encounters a
grant-option ACL entry from its own grantor, reconciliation fails closed
rather than taking ownership of a capability outside the contract. A revoke
that would invalidate dependent delegated grants can fail under PostgreSQL's
default `RESTRICT`; PACT does not use `CASCADE`.

Only databases named by the previous or desired PACT state are in a
reconciliation's scope; within such a database all schemas, tables and
columns (except system schemas) are in scope. This allows deletion of the final access for a
database to clean its PACT grants without touching unrelated databases. It
also means grants manually created by the dedicated grantor in an in-scope
database are considered managed and may be revoked when absent from desired
state.

The backend does not promise exact effective privileges: ownership,
`PUBLIC`, role memberships, defaults, and grants from other grantors can still
confer access. No role provisioning or password change is performed.

Privileges at different levels are independent: `CONNECT` does not give
schema `USAGE`, and schema `USAGE` does not give table `SELECT`. Declare each
level that is needed. Reconciliation is atomic per database; across databases
PACT relies on the backend transaction's compensation.

## Row-level security: proposed model

PostgreSQL row-level security (RLS) is not another `GRANT` privilege. It is a
policy attached to one table that filters which rows a role may read or modify.
The role still needs ordinary privileges such as `SELECT`, `INSERT`, `UPDATE`,
or `DELETE`; RLS further constrains those operations. A policy does not itself
grant SQL privileges.

The nearest analogy in PACT is Trino's `rowFilter`, but PostgreSQL needs a
richer shape. Trino's filter is essentially a row predicate for a table;
PostgreSQL policies distinguish command, roles, permissive/restrictive
combination, visible/target rows, and proposed new rows. A PostgreSQL policy
therefore should be a separate access attribute (tentatively `rowPolicy`),
not reuse the Trino `rowFilter` unchanged.

An illustrative future DataAccess shape could be:

```yaml
resources:
  - analytics-db:
      database: analytics
      schema: sales
      table: orders
    access:
      - users:
          - app_reader
        permissions:
          table:
            - SELECT
        rowPolicy:
          command: SELECT
          using: "tenant_id = current_setting('app.tenant_id', true)::uuid"
```

`users` would identify PostgreSQL roles placed in the policy's `TO` role list;
it would not create roles. The target would identify exactly one table within
the configured database. The `rowPolicy` body is illustrative and is not yet
part of the DataAccess contract.

### Proposed policy fields

| Field | Purpose |
| --- | --- |
| `command` | One of `ALL`, `SELECT`, `INSERT`, `UPDATE`, or `DELETE`. |
| `using` | Boolean predicate for existing rows visible to `SELECT`, `UPDATE`, or `DELETE`. |
| `withCheck` | Boolean predicate for rows proposed by `INSERT` or `UPDATE`. |
| `mode` | `PERMISSIVE` or `RESTRICTIVE`; require an explicit choice or define a documented default before implementation. |

The valid expression combinations depend on the command: `SELECT` and `DELETE`
use `USING`; `INSERT` uses `WITH CHECK`; `UPDATE` can use both; `ALL` can use
both. PostgreSQL uses `USING` as the `WITH CHECK` predicate when `ALL` or
`UPDATE` omits `WITH CHECK`. Requiring explicit predicates where practical
would make intended semantics easier to review and reduce accidental policy
broadening.

Policy expressions are SQL expressions, not parameterized SQL values: PostgreSQL
stores and evaluates them as part of queries against each row. A configuration
such as `current_setting('app.tenant_id', true)` only works if applications
reliably establish that session value and cannot let a caller select another
tenant. PACT cannot infer tenant isolation from the SQL text.

### PostgreSQL semantics the model must preserve

- RLS must be enabled on each target table. If RLS is enabled and no applicable
  policy permits an operation, PostgreSQL uses default-deny behavior.
- Permissive policies combine with `OR`; restrictive policies combine with
  `AND`. A restrictive policy is an additional constraint, not a standalone
  allow rule: access still needs a matching permissive policy.
- A policy's `USING` expression controls which existing rows are visible or
  eligible for modification. `WITH CHECK` controls which new row values may be
  inserted or stored by an update. If an update changes a row so that it no
  longer passes `WITH CHECK`, the statement fails.
- Multiple applicable policies all participate. Adding another permissive
  policy can broaden access through `OR`; reconciliation must not treat a set
  of policy expressions as a simple deny-overrides list.
- Table owners normally bypass RLS. Superusers and roles with `BYPASSRLS` always
  bypass it. `FORCE ROW LEVEL SECURITY` can subject the table owner to RLS but
  does not constrain superusers or `BYPASSRLS` roles.
- Operations including `TRUNCATE` and `REFERENCES` are not filtered by RLS.
  Referential-integrity checks can also bypass row filtering and require
  separate security analysis.
- RLS only covers the four commands above; it does not replace ordinary
  database, schema, table, sequence, or function privileges.

### Reconciliation and safety proposal

RLS adds table-owner-level DDL to the backend. PostgreSQL permits only the
table owner to enable/disable RLS and create/alter/drop its policies. A
grant-manager login that can issue database `GRANT`s may therefore be
insufficient. The design must choose whether the configured PostgreSQL role
owns managed tables, can `SET ROLE` to their owner, or uses a separate
owner-capable connection. Managed-service products may constrain these
options.

The future reconciler should manage only PACT-owned policies, with deterministic
names in a reserved PACT prefix and collision checks before changing or
deleting them. It should not delete policies merely because they are absent
from one DataAccess if other resources contribute to the same table's desired
state. Instead it should aggregate the cluster-wide state by table, normalize
equivalent policies, and reconcile the complete desired policy set for each
explicitly managed table. Identical predicates/command/mode can potentially
share one policy by merging role names; policy identity and merge rules need to
be specified and tested.

Enabling RLS is a separate, security-significant table setting. The contract
must decide whether declaring any `rowPolicy` automatically enables RLS, or
whether the resource has an explicit `rowSecurity` setting. Automatic enable
is convenient, but removing the last managed policy must not silently disable
RLS: doing so can turn a default-deny table into an unfiltered table. The safer
initial behavior is to enable RLS when requested and leave it enabled when
policies are removed. A separate explicit opt-out/disable operation would need
strong safeguards and clear ownership semantics.

Expression strings are powerful SQL and cannot be safely reduced to ordinary
bind parameters in `CREATE POLICY`. Accepting arbitrary expressions therefore
requires that only trusted administrators can write the corresponding CRDs.
Kubernetes RBAC is part of the security boundary; the backend must never
construct expressions from untrusted identifiers or attempt to treat a string
check as a SQL sandbox. A restricted expression language could be considered
later if policies must be authored by less-trusted tenants.

The desired state has to include the complete role set for each policy. Since
PostgreSQL policy objects have no Ranger-style policy labels, a managed name
prefix alone is not sufficient proof that PACT may safely adopt a colliding
policy. The implementation should refuse collisions unless a stronger
ownership marker/registry is designed. Changes to command or permissive mode
may require drop-and-create rather than a simple alter; those operations
should be transactional and covered by compensation tests.

### Open decisions before implementation

1. Whether `rowPolicy` is a singleton object per access or a list allowing
   several predicates for one role/table.
2. Whether the public fields use SQL terminology (`using`, `withCheck`,
   `command`, `permissive`) or a higher-level expression model.
3. Whether policy mode defaults to permissive or must always be declared.
4. How complete desired-state ownership is represented per table, and how
   independently authored DataAccess resources combine without deleting each
   other's policies.
5. Whether PACT explicitly enables RLS but never disables it, or manages an
   explicit enabled/forced state.
6. Which ownership/authentication arrangement lets PACT administer table
   policies without making the controller a superuser.

PostgreSQL documents these semantics in
[Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html),
[CREATE POLICY](https://www.postgresql.org/docs/current/sql-createpolicy.html),
and [ALTER TABLE](https://www.postgresql.org/docs/current/sql-altertable.html).
RLS support remains a design proposal and is not implemented.

## Reconciliation and ownership boundary

The backend compiles `PactState` into desired direct grants, reads actual
grants, calculates a normalized diff, and applies `GRANT`/`REVOKE` statements
in a JDBC transaction. A prepared `BackendTransaction` snapshots the actual
PACT-owned grants in scope to compensate if another backend fails during a
PACT reconciliation. Database and role identifiers are quoted as SQL
identifiers with embedded quotes escaped; they are not interpolated as
unquoted SQL. Passwords and credential-bearing JDBC URLs are not logged.

PostgreSQL effective privileges can come from direct grants, grants to
`PUBLIC`, inherited role memberships, or object ownership. PACT should manage
only direct grants that it owns, not infer ownership of every effective
privilege. In particular, removing a desired `CONNECT` grant must not revoke a
pre-existing grant from `PUBLIC` or another grantor. The implementation needs
an explicit strategy for identifying PACT-owned grants and must document the
grant-manager role's required permissions. Exact effective-access enforcement
is not promised by this narrow contract.

PostgreSQL roles are cluster-wide, while privileges name objects inside one
database of that cluster. Deployment-specific constraints
(for example, managed PostgreSQL products that restrict grant authority) need
integration coverage before being claimed as supported.

## Identity provisioning: proposed separate domain

Creating login roles and managing passwords is intentionally separate from
compiling `Access` into database grants:

- A future `DataIdentity` resource would declare an identity independently of
  any one `DataAccess`. `DataAccess` would continue to refer to the PostgreSQL
  role name in `users`; it would not silently create a role placeholder.
- A future identity mode could distinguish an externally managed/existing
  role from a PACT-managed login role. The exact schema and ownership contract
  remain to be decided.
- A role must exist before its grants can be applied. If an access references
  an identity that is not present, reconciliation should wait or report a
  clear dependency error and retry after identity changes; it must not silently
  create a role as a side effect of permission compilation.
- An identity could refer to an existing Kubernetes Secret or ask PACT to
  generate a password into a named Secret. The password must never be embedded
  in the identity spec/status, `PactState`, logs, or `lastAppliedSpec`.
- Generated credentials must survive controller restarts and be delivered to
  consumers. Kubernetes Secret RBAC and encryption at rest remain deployment
  responsibilities; a Secret's base64 representation is not encryption.
- PostgreSQL cannot reveal an existing plaintext password. Reconciliation can
  detect a Secret change and set the new password, but cannot compare a
  configured password with the server's stored verifier.
- Identity creation/password changes and database GRANTs span separate
  operations and cannot share an atomic transaction. Their orchestration must
  be retryable and expose progress/errors without leaking credentials.
- Identity deletion must not automatically `DROP ROLE` by default. Roles may
  own objects or have dependencies, and an identity may still be referenced by
  access resources. A future explicit deletion policy must check references and
  database dependencies before role removal.

An alternative is having each `DataAccess` create a role placeholder and a
later identity object attach credentials. That is technically possible in
PostgreSQL, but splits lifecycle ownership across resources and makes deletion
and adoption ambiguous. It is therefore not the default design; revisit only
if a concrete workflow requires grants to predate identity declarations.

Because PostgreSQL roles are cluster-wide, identity provisioning may need
separate administrative credentials/permissions from the grant manager. A
future design should decide whether provisioning is a distinct backend
capability or a coordinated identity provider executed before authorization
backends, with grants removed before any managed role can be deleted.

## Planned module layout

The implementation can follow existing PACT plugin conventions:

```text
pact-postgresql/
  config/       PostgreSQL connection and grant-manager settings
  client/       JDBC connection lifecycle and PostgreSQL metadata access
  compile/      PactState to validated desired grants
  sync/         owned-grant discovery, diff and GRANT/REVOKE application
  PostgreSqlBackend.java
  PostgreSqlBackendFactory.java
  src/main/resources/META-INF/services/io.github.pactproject.api.BackendFactory
```

Unit tests should cover compilation, SQL identifier escaping, grant ownership,
normalization and rollback. Opt-in integration tests should use a disposable
PostgreSQL cluster and verify database grants through PostgreSQL's privilege
inspection functions as well as through actual connection/permission checks.
Identity provisioning tests should be added only after its resource and Secret
contract is agreed.
