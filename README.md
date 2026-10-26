# PACT

**PACT** stands for **Policy & Authentication/Access Control Tool**. It
mediates between a declarative source of truth and the systems that enforce
access: PACT reads the desired state, translates it for configured backends,
and reconciles each backend to match.

PACT is designed to be extensible, stateless in its desired-state model, and
precise about what it manages in each backend.

## How it works

```text
Declarative source of truth
            |
            v
          PACT
       /    |    \
      v     v     v
  Backend Backend Backend
```

A state provider supplies the desired set of access declarations. PACT's core
groups them by backend, detects which backend states changed, and delegates
translation and reconciliation to the corresponding plugins.

The source of truth remains authoritative; PACT is not a separate database of
desired access. PACT may keep runtime state and applied-state snapshots to
calculate changes, recover across restarts, and process events. That state is
reconstructable from the configured source of truth rather than an independent
record that operators must maintain.

When a transition touches multiple backends, PACT prepares backend
transactions and attempts compensating rollback if an apply fails. This
reduces partial changes, but cannot guarantee atomicity across independent
external systems.

Rollback restores the previous agreed declaration. If compensation itself
fails, the next reconciliation applies the current source declaration, even
when it equals the previously agreed state or is empty, and includes all
possibly affected scopes in cleanup. This recovery context currently exists
only in core memory and is lost on restart. Ordinary unchanged backend states
are still skipped; PACT does not periodically repair external drift.

## Design principles

### Declarative reconciliation, not another source of truth

Operators declare the access they want in a state provider. PACT translates
that declaration into backend-specific objects and brings those systems into
line with it. PACT does not own the declaration or require a separate database
of desired state.

### Centralized control with backend-level detail

PACT provides one place to declare access across multiple systems while
preserving each backend's own authorization model. A backend plugin decides
how resources and permissions map to that system; PACT does not force every
backend into a lowest-common-denominator permission model.

Each configured backend instance has an `id` that declarations reference.
This lets one PACT process manage separate services or clusters independently,
including multiple instances of the same backend type.

### Extensible through plugins

Backends and state providers are plugins. PACT discovers plugin factories
through Java `ServiceLoader`, so a new backend can be added without adding
backend-specific branches to the PACT core. Plugin JARs and their runtime
dependencies are loaded from the configured plugins directory.

The backend plugin authoring guide is
[`BACKEND_DEVELOPMENT.md`](BACKEND_DEVELOPMENT.md). The extension points are
`BackendFactory` / `Backend` and `StateProviderFactory` / `StateProvider` in
`pact-api`.

### Principals and connection credentials have separate responsibilities

Backends may need a principal to exist before they can create a policy or
grant. PACT's backend-specific ensure behavior handles that prerequisite where
supported: a backend can create a missing principal with a random password,
create one without a password, or report that the principal must already
exist. This is about making access declarations valid in the target system;
it is not a general identity or password lifecycle manager.

Credentials PACT itself uses to connect and authenticate to a backend are
configuration inputs. PACT does not provision or rotate those credentials;
provide them through the deployment's secret/configuration mechanism.

## Current components

| Module | Purpose |
| --- | --- |
| `pact-api` | Shared plugin interfaces and access-state model |
| `pact-core` | Groups desired state by backend and coordinates reconciliation and rollback |
| `pact-app` | Loads configuration and plugins, then runs PACT |
| `pact-filesystem` | Reads a YAML snapshot as desired state |
| `pact-kubernetes` | Watches `DataAccess` custom resources and reconciles their combined state |
| `pact-ranger` | Compiles access into managed policies for a configured Apache Ranger service |
| `pact-artifact-keeper` | Reconciles Artifact Keeper permissions for all target types ([scope and compatibility](pact-artifact-keeper/README.md)) |
| `pact-postgresql` | Reconciles PostgreSQL object privileges and declared role identities |
| `pact-elasticsearch` | Reconciles Elasticsearch 9 native users, index/cluster privileges and existing role assignments |

Backend-specific configuration, supported access fields, permissions, and
operational requirements belong in each module's README.

For Elasticsearch DataAccess recipes and scope, see the
[operator guide (Russian)](pact-elasticsearch/OPERATOR_GUIDE.ru.md).

## Configuration and execution

PACT is configured with a YAML file that selects one state provider and one or
more backend instances. The `id` is the name declarations use to route access;
`type` selects the plugin implementation; `config` is interpreted by that
plugin.

```yaml
stateProvider:
  type: filesystem
  config:
    path: ./desired-state.yaml

backends:
  - id: analytics
    type: postgresql
    config:
      jdbc-url: jdbc:postgresql://postgres.example:5432/postgres
      username: pact
      password: "<injected-secret>"
```

The filesystem state file contains an `accesses` array. Each access identifies
a principal, a backend instance, a backend-specific resource target, and
attributes interpreted by that backend. For example:

```yaml
accesses:
  - principal: alice
    resource:
      backendId: analytics
      target:
        database: warehouse
    attributes:
      permissions:
        database:
          - CONNECT
```

Run the application with the config path and plugins directory:

```text
pact-app <config> <plugins-directory>
```

## Configuration migration

PostgreSQL and Elasticsearch now default to `reconciliation-mode: authoritative`.
Before upgrading, declare all grants/assignments required within their documented
scope, or explicitly set PostgreSQL `grantor` / Elasticsearch `managed` to retain
the previous ownership boundary. PostgreSQL authoritative mode requires
superuser authority. Its removed `preserve-default-public-privileges` key is
rejected; PUBLIC grants are ordinary ACLs and must be declared to retain them.

Explicit Kubernetes identities default to `ensure: true`; set `false` to require
an existing account. Use either `backend: id` or `backends: [id1, id2]`.
Unsupported explicit identities fail validation instead of being ignored.
See the module READMEs for scope, credentials and migration details. The plugin
API method is now `prepareTransaction`; rebuild plugins overriding the old
`prepare` method against this API.

## Docker image

Build the image from the repository root so the Docker build can access the
multi-module Maven project:

```shell
docker build -t pact:local .
```

The image includes the application and the currently implemented state
providers and backends as plugins. Mount a PACT YAML configuration file at
`/etc/pact/config.yaml`; the default command uses that file and
`/opt/pact/plugins`. Supply backend credentials through the deployment's
secret mechanism rather than baking them into the image.

GitHub Actions builds the image for pull requests and publishes it to GHCR
when an image version tag is pushed. Prefix release tags with `image-v`; for
example, to publish and pull release `8`:

```shell
python integration/scripts/check-release.py 8
git tag image-v8
git push origin image-v8
docker pull ghcr.io/pactproject/pact:8
```

GHCR package visibility is managed in GitHub; make the package public there if
the image should be available without authentication.

The Kubernetes state provider runs as a controller and watches `DataAccess`
resources cluster-wide. It requires the corresponding CRD, status subresource,
and RBAC; see [`pact-kubernetes/README.md`](pact-kubernetes/README.md) and the
[PACT Helm chart](charts/pact).

### Versioning

PACT releases use one increasing positive integer, starting with `8` after
the historical image `1.0.7`: `8`, `9`, `10`, and so on. This is a release
sequence, not a semantic-version compatibility promise. Existing published tags
remain unchanged.

The root Maven version identifies the PACT API, core, and application. Each
backend/state-provider plugin has its own version in that module's `pom.xml`;
plugin versions may advance without changing the root application version. The
plugins still depend on the API version declared by `pact.api.version` in the
root POM.

The container image uses the same release number as the root Maven/application
and `pact.api.version`. Git tag `image-vN` publishes image tag `N` (plus a
commit-SHA tag). Each image contains the plugin JARs built from that source
revision. Updating a plugin
therefore requires advancing the PACT release number. Plugin JARs are not published
independently.

The Helm chart package version in `charts/pact/Chart.yaml` tracks the chart
itself. Its `appVersion` and the default `image.tag` in
`charts/pact/values.yaml` should identify the image version the chart deploys.
Keep these image values quoted so numeric release tags stay YAML strings. The
chart package version remains independent SemVer, as required by Helm.
Run `python integration/scripts/check-release.py N` before creating `image-vN`;
the publishing workflow rejects non-integer or inconsistent release versions.

## Build

The repository is a Maven multi-module Java project. Build and run unit tests
with:

```shell
mvn test
```

Live integration tests and their isolation requirements are described in
[`INTEGRATION_TESTS.md`](INTEGRATION_TESTS.md).

The shared backend test contract, links to concrete assertions and current
coverage gaps are in [`TESTING.md`](TESTING.md). Check its inventory and
references with `python integration/scripts/check-test-contract.py`.

For diagnostics, readers of PACT logs are trusted as administrators of all
configured backends. Users, resource names, ACLs, declarations and operational
details may be logged. Passwords, API tokens/keys and Authorization values must
be masked, including in response/error details and nested causes.

The local Docker Compose backend and end-to-end test stack is documented there
as well. Its E2E run exercises the filesystem provider and the Ranger,
Artifact Keeper, PostgreSQL and Elasticsearch backends against disposable services.
Elasticsearch checks real read/write operations, privilege updates, an unrelated
index boundary and empty-state revocation through the same application instance.
