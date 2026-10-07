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
| `table` | `table` | `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, `REFERENCES`, `TRIGGER`, `MAINTAIN` |
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
- Targets are exact: wildcard targets such as `schema: "*"` or `table: "*"`
  are not supported.
- A privilege on the wrong level (for example `SELECT` on `database`) fails
  validation.

## Default privileges

Use `defaultPrivileges` on a database-and-schema target instead of a wildcard.
It configures native PostgreSQL default ACLs for objects subsequently created
by the declared `creator`, and applies the same privileges to existing objects
in that schema that are owned by that creator:

```yaml
resources:
  - analytics-db:
      database: analytics
      schema: sales
    access:
      - users:
          - app
        defaultPrivileges:
          creator: sales_migrator
          permissions:
            table:
              - SELECT
              - UPDATE
            sequence:
              - USAGE
            function:
              - EXECUTE
```

`users`/`Access.principal` is the grantee; `creator` is the PostgreSQL role
that creates objects and owns the default-privilege rule. PostgreSQL only
applies default ACLs to objects created by that role (or a role it has assumed).
Existing objects owned by other roles are unchanged. In authoritative mode,
PACT may use `PUBLIC` as the grantee.

Supported default-privilege object types are `table`, `sequence`, `function`
and `procedure` (functions and procedures use PostgreSQL's shared `ROUTINES`
default-ACL category). Database-, schema- and column-level defaults are not
available. PostgreSQL global defaults remain independent of schema-specific
defaults configured by PACT.

An exact-object `permissions` declaration for the same grantee and object type
overrides that object's default-derived privileges. For example, this grants
only `SELECT` on one table even when the schema rule also grants `UPDATE`:

```yaml
resources:
  - analytics-db:
      database: analytics
      schema: sales
      table: archived_orders
    access:
      - users:
          - app
        permissions:
          table:
            - SELECT
```

### Application database with a developer and analysts

This example gives the application permission to create schemas, gives the
developer read/write access to application objects, and gives two analysts
read-only access. The analysts may also create their own schemas:

```yaml
resources:
  - app-postgres:
      database: appdb
    access:
      - users:
          - app
        permissions:
          database:
            - CONNECT
            - CREATE
      - users:
          - developer
        permissions:
          database:
            - CONNECT
      - users:
          - analyst_a
          - analyst_b
        permissions:
          database:
            - CONNECT
            - CREATE

  - app-postgres:
      database: appdb
      schema: app_data
    access:
      - users:
          - developer
        permissions:
          schema:
            - USAGE
        defaultPrivileges:
          creator: app
          permissions:
            table:
              - SELECT
              - INSERT
              - UPDATE
              - DELETE
            sequence:
              - USAGE
              - SELECT
              - UPDATE
      - users:
          - analyst_a
          - analyst_b
        permissions:
          schema:
            - USAGE
        defaultPrivileges:
          creator: app
          permissions:
            table:
              - SELECT
            sequence:
              - SELECT
```

The `appdb` database and the roles must exist; role identities can be declared
separately in `DataAccess.spec.identities`. The `app` role must own `app_data`
and create its objects as `app` for the schema's default privileges to apply.
PACT applies those defaults to existing objects in `app_data` owned by `app`
and to future objects there created by `app`.

The `CREATE` database privilege lets each analyst create schemas with any name
in `appdb`; PostgreSQL cannot limit it to a personal schema name. An analyst
owns a schema they create and can manage or drop it. In contrast, analysts
receive only `USAGE` on `app_data`, so they cannot create objects there or drop
that schema; its owner (`app`) or a superuser controls it. The developer does
not receive database `CREATE`.

`app_data` must exist before this full state is reconciled because PostgreSQL
requires the schema for schema-scoped `ALTER DEFAULT PRIVILEGES`. For initial
setup, first grant database access and `CREATE` to `app`, let the application
create `app_data`, then apply the complete state above. PACT does not
automatically apply this schema policy to arbitrary schemas created later.

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

Column-level and table-level grants are independent; a table-level privilege
already covers all columns.

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
      reconciliation-mode: grantor
      preserve-default-public-privileges: true

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
Settings `jdbc-url`, `username`, and `password` are required.
`reconciliation-mode` is `grantor` (the default) or `authoritative`.
`preserve-default-public-privileges` defaults to `true`; authoritative mode
uses it to preserve PostgreSQL's built-in `PUBLIC` defaults for database
`CONNECT`/`TEMPORARY` and routine `EXECUTE`. Set it to `false` when PACT should
reconcile those privileges too. The JDBC URL identifies the cluster; PACT
replaces its database part with each managed database, because object-level
ACLs live in per-database catalogs. Object names are passed to `GRANT`/`REVOKE`
as quoted SQL identifiers.
Inject credentials using the deployment's secret mechanism; do not commit
real passwords to configuration. The plugin runtime needs pgJDBC
(`org.postgresql:postgresql`) visible in the plugin class loader.

Rules:

- `Access.principal` is the name of an existing PostgreSQL role. In
  authoritative mode, the exact subject `PUBLIC` maps to PostgreSQL's
  special `PUBLIC` grantee; it is not a role name or an object wildcard. The
  exact subject `PUBLIC` is reserved for this purpose.
- `DataAccess.spec.identities` may declare roles for this backend. `ensure`
  defaults to `false`; when true, PACT creates a missing role with `LOGIN`.
  Existing roles must have `LOGIN` when PACT is asked to set a password; PACT
  does not change existing role attributes. Removing an identity declaration
  does not drop the role.
- An optional Kubernetes `passwordSecretRef` sets the role password. PACT
  applies a changed Secret resource version on the next reconciliation of the
  declaring `DataAccess`; it does not watch Secrets directly. Identity
  reconciliation, including role creation and password updates, runs before
  grant synchronization so grants can target newly ensured roles. PostgreSQL
  does not expose the previous password to PACT, so a successful password
  change cannot be rolled back if grant synchronization or a later backend
  transaction fails. PACT reports the failure and applies the declarative
  state on retry.
- Role password management is separate from the JDBC credentials PACT uses
  for its own connection to PostgreSQL.
- `Resource.backendId` selects the configured PostgreSQL connection.
- PACT declarations do not express `WITH GRANT OPTION`; authoritative mode
  removes it from managed grants. Role membership, object ownership,
  `ALTER DEFAULT PRIVILEGES` and row-level security remain out of scope.
- Privilege names are case-insensitive; unknown privilege or target names
  fail validation rather than being interpolated into SQL.
- An access with no permissions expresses no grants for that role/target;
  previously managed grants in scope are revoked according to the configured
  reconciliation mode.

## Grant ownership contract

`reconciliation-mode: grantor` preserves the original behavior. PACT reads and
reconciles only direct ACL entries issued by the configured login, excluding
`PUBLIC` and grants from other grantors. That login needs enough authority to
grant/revoke the configured privileges and connect to managed databases. Use a
dedicated login; do not share it with manual grants or another independently
reconciling backend over overlapping databases.

`reconciliation-mode: authoritative` is an explicit, destructive mode for
making PACT the source of truth for direct object ACLs. It requires the
configured login to be a PostgreSQL superuser. Within each database in scope,
PACT compares ACLs for all grantees, including `PUBLIC`, without treating the
grantor as an ownership boundary. ACL grants absent from desired state are
revoked; grant options are removed because PACT does not model them. PostgreSQL
requires a revoke to run as the grantor recorded on that ACL, so PACT temporarily
`SET ROLE`s to each grantor. These revokes use `CASCADE`; grants removed through
grant-option dependencies are then restored only when present in the desired
state. Object-owner privileges are not revoked.

Authoritative mode does not manage role membership, object ownership,
`ALTER DEFAULT PRIVILEGES`, row-level security, or database/schema/object
creation. Those paths can still confer access independently of the direct ACLs
PACT reconciles. PACT also reconciles only databases named by the previous or
desired state; ensure every database to be managed is represented in that
state. Use a complete desired state and pilot the mode before enabling it on a
production scope.

## Provisioning the grant-manager login

For a grants-only backend, use a dedicated login with `CONNECT` to each managed
database and `WITH GRANT OPTION` on only the privileges PACT is expected to
delegate on pre-existing objects. The owner (or a role already entitled to
delegate those privileges) provisions the grant options; PACT does not create
them. For example, if PACT will give an application `CONNECT` to `analytics`,
`USAGE` on `sales`, and `SELECT` on `sales.orders`:

```sql
-- Run as a PostgreSQL administrator.
CREATE ROLE pact_grant_manager LOGIN;
```

Set the login password securely in `psql` with `\password pact_grant_manager`,
then have the relevant database and object owners provision the exact
delegated privileges:

```sql
GRANT CONNECT ON DATABASE analytics
    TO pact_grant_manager WITH GRANT OPTION;

GRANT USAGE ON SCHEMA sales
    TO pact_grant_manager WITH GRANT OPTION;

GRANT SELECT ON TABLE sales.orders
    TO pact_grant_manager WITH GRANT OPTION;
```

Repeat this for each managed database, object and privilege that appears in
PACT's desired state. The `WITH GRANT OPTION` must cover the corresponding
privilege at its actual target level:

| PACT target level | Privileges the grant-manager may need to delegate |
| --- | --- |
| `database` | `CONNECT`, `CREATE`, `TEMPORARY` |
| `schema` | `USAGE`, `CREATE` |
| `table` | `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, `REFERENCES`, `TRIGGER`, `MAINTAIN` |
| `column` | `SELECT`, `INSERT`, `UPDATE`, `REFERENCES` on the named columns |
| `sequence` | `USAGE`, `SELECT`, `UPDATE` |
| `function`, `procedure` | `EXECUTE` on the exact routine signature |

Only provision options for privileges PACT actually needs to issue. A privilege
granted `WITH GRANT OPTION` is also a privilege the grant-manager itself holds;
for example, delegating schema `CREATE` also lets the grant-manager create
objects there. Do not grant all options indiscriminately.

In the grants-only setup, the login does not need `SUPERUSER`, `CREATEDB`, or
`CREATEROLE`; target roles and objects must already exist. PostgreSQL may accept
a `GRANT` from a login that lacks the needed grant option but emit a “no
privileges were granted” warning and make no ACL change. PACT may not report
that warning as an error, so a successful reconciliation alone is not proof
that the recipient received access.

The provisioning is per existing object. Newly created objects need their
grant options provisioned by their owner before PACT can manage grants on them;
PACT does not manage `ALTER DEFAULT PRIVILEGES`. The live integration test
exercises a non-owner grant-manager with no elevated role attributes on
PostgreSQL 18. Managed PostgreSQL products can impose additional restrictions
and should be tested separately.

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
grants in scope to compensate if another backend fails during a PACT
reconciliation. In grantor mode the configured login is the ownership
boundary; in authoritative mode ACLs are reconciled regardless of grantor.
Database and role identifiers are quoted as SQL identifiers with embedded
quotes escaped; they are not interpolated as unquoted SQL. Passwords and
credential-bearing JDBC URLs are not logged.

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
the temporary database and role. It also verifies grantor-mode reconciliation
through a delegated non-superuser login and authoritative-mode cleanup across
grantors, grant-option removal, the `PUBLIC` subject, and preservation or
revocation of built-in `PUBLIC` defaults. Run it only against a disposable
cluster with a test login that has `CREATEDB` and `CREATEROLE`; see
[`INTEGRATION_TESTS.md`](../INTEGRATION_TESTS.md). The local Compose `tests`
profile runs this test against its disposable PostgreSQL service.
