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
| `pact-artifact-keeper` | Reconciles Artifact Keeper repository permissions ([current scope and roadmap](pact-artifact-keeper/README.md)) |
| `pact-postgresql` | Reconciles PostgreSQL object privileges and declared role identities |
| `pact-elasticsearch` | Reconciles Elasticsearch 9 native users' index privileges through PACT-owned roles |

Backend-specific configuration, supported access fields, permissions, and
operational requirements belong in each module's README.

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
when a version tag starting with `v` is pushed. For example, to publish and
pull version `1.0.0`:

```shell
git tag v1.0.0
git push origin v1.0.0
docker pull ghcr.io/pactproject/pact:v1.0.0
```

GHCR package visibility is managed in GitHub; make the package public there if
the image should be available without authentication.

The Kubernetes state provider runs as a controller and watches `DataAccess`
resources cluster-wide. It requires the corresponding CRD, status subresource,
and RBAC; see [`pact-kubernetes/README.md`](pact-kubernetes/README.md) and the
[PACT Helm chart](charts/pact).

## Build

The repository is a Maven multi-module Java project. Build and run unit tests
with:

```shell
mvn test
```

Live integration tests and their isolation requirements are described in
[`INTEGRATION_TESTS.md`](INTEGRATION_TESTS.md).

The local Docker Compose backend and end-to-end test stack is documented there
as well. Its E2E run exercises the filesystem provider and the Ranger,
Artifact Keeper, and PostgreSQL backends against disposable services.
