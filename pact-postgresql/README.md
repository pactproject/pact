# PACT PostgreSQL backend

`pact-postgresql` provides a plugin backend for PostgreSQL privileges over
JDBC at database, schema, table, column, sequence, function and procedure
levels. It also reconciles explicitly declared PostgreSQL role identities and
their optional passwords independently of grants.

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
- `DataAccess.spec.identities` may declare roles for this backend. `ensure`
  defaults to `false`; when true, PACT creates a missing role with `LOGIN`.
  Existing roles must have `LOGIN` when PACT is asked to set a password; PACT
  does not change existing role attributes. Removing an identity declaration
  does not drop the role.
- An optional Kubernetes `passwordSecretRef` sets the role password. PACT
  applies a changed Secret resource version on the next reconciliation of the
  declaring `DataAccess`; it does not watch Secrets. Password changes are not
  rolled back if a later grant operation fails. PACT reports the failure and
  applies the declarative state on retry.
- Role password management is separate from the JDBC credentials PACT uses
  for its own connection to PostgreSQL.
- `Resource.backendId` selects the configured PostgreSQL connection.
- `WITH GRANT OPTION`, `PUBLIC`, role membership, object ownership,
  default privileges and row-level security are out of scope.
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
grants reconciler does not create databases or grant options. If it encounters
a grant-option ACL entry from its own grantor, reconciliation fails closed
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

The grants reconciler does not promise exact effective privileges: ownership,
`PUBLIC`, role memberships, defaults, and grants from other grantors can still
confer access.

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
PACT reconciliation. The configured database login is the grantor identity:
the backend reads and reconciles only ACL entries issued by that login. It does
not infer ownership of privileges from `PUBLIC`, role membership, or object
ownership. Database and role identifiers are quoted as SQL identifiers with
embedded quotes escaped; they are not interpolated as unquoted SQL. Passwords
and credential-bearing JDBC URLs are not logged.

PostgreSQL roles are cluster-wide, while privileges name objects inside one
database of that cluster. Deployment-specific constraints
(for example, managed PostgreSQL products that restrict grant authority) need
integration coverage before being claimed as supported.

## Identity reconciliation

Identity declarations live at `DataAccess.spec.identities`, independently of
the `resources` grants list. They are backend-neutral in PACT's state model;
this PostgreSQL plugin reconciles only identities whose backend id matches its
configured id. The PostgreSQL role name is cluster-wide. The JDBC account must
have permission to inspect and, when `ensure: true`, create roles and set
passwords (for example, `CREATEROLE` where permitted by the server).

PACT cannot read an existing role's plaintext password. It applies a password
when the role is created or when the referenced Secret source/resource version
changes; it does not compare the desired value to PostgreSQL's stored verifier.
Role creation and password changes are not compensatable: if a later grant
operation fails, the password remains changed. The failed DataAccess is marked
in error and a later reconciliation retries the declarative state. Removing an
identity does not drop the role.

## Testing

The backend has unit tests for grant compilation, synchronization, JDBC
behavior, and role identity reconciliation. Its opt-in
`PostgreSqlLiveIntegrationTest` creates a uniquely named temporary database
and role, verifies direct grants across PostgreSQL object levels, updates
grants and the role password, tests login with the new password, and removes
the temporary database and role. Run it only against a disposable cluster
with a test login that has `CREATEDB` and `CREATEROLE`; see
[`INTEGRATION_TESTS.md`](../INTEGRATION_TESTS.md). The local Compose `tests`
profile runs this test against its disposable PostgreSQL service.
