# PACT Elasticsearch backend

`pact-elasticsearch` reconciles index-level permissions for Elasticsearch 9
native-realm users through the Security API. It uses Java 21 `HttpClient` and
the Jackson dependencies already used by PACT; it does not require an
Elasticsearch client library.

## Backend configuration

```yaml
backends:
  - id: search
    type: elasticsearch
    config:
      endpoint: https://elasticsearch.example:9200
      username: ${PACT_ES_USERNAME}
      password: ${PACT_ES_PASSWORD}
      principal-mode: require
```

Configure exactly one authentication method: `username` and `password`
together, or `api-key` containing Elasticsearch's encoded API key. `endpoint`
must be an absolute HTTP(S) URL. The default `principal-mode` is `require`;
`ensure` allows an `Identity` with `ensure: true` to create a missing user.
The generated password is cryptographically random, sent only to the Security
API, and neither logged nor persisted by PACT. Existing user passwords are
changed only when that identity declares `passwordSecretRef` and the Secret's
resource version changes. New users use the referenced Secret password when
present; otherwise they receive a cryptographically random password. Users are
never deleted by the backend.

## Access model

Accesses target exactly one `index` and provide a non-empty set of supported
Elasticsearch index privileges:

```yaml
accesses:
  - principal: alice
    resource:
      backendId: search
      target:
        index: logs-*
    attributes:
      permissions:
        indices:
          - read
          - view_index_metadata
```

Supported privilege names are `all`, `auto_configure`, `create`, `create_doc`,
`create_index`, `delete`, `delete_index`, `index`, `maintenance`, `manage`,
`manage_data_stream_lifecycle`, `manage_follow_index`, `manage_ilm`,
`manage_leader_index`, `manage_rollup`, `monitor`, `read`,
`read_cross_cluster`, `view_index_metadata`, and `write`. Cluster/application
permissions, DLS/FLS, and deny semantics are not part of this backend.

Entries for the same principal and index pattern are aggregated by privilege
union into one role. The role name is deterministic (`pact-` plus a hash of
backend id and principal), and role metadata stores the PACT ownership marker,
backend id, and principal. A pre-existing role at that name without all
matching metadata causes reconciliation to fail; it is never overwritten or
deleted.

The backend reads a native user's current roles and updates only its own role,
preserving other assigned roles and available user metadata. When a principal
loses all desired access according to the previous and desired PACT states,
PACT removes that role assignment and deletes the owned role. Existing native
users are not otherwise changed.

Reconciliation snapshots owned roles and native-user role sets during
`prepare`; rollback restores prior role definitions and only the PACT role
membership, retaining unrelated roles. A newly ensured native user remains if
access application fails and can be retried. A password rotation is performed
after role and access changes, but Elasticsearch does not expose the old
password to PACT, so a successful password update cannot be rolled back if a
later backend transaction fails. Elasticsearch does not provide a conditional
update for role assignment read/modify/write operations, so avoid concurrent
external writers to the same users or managed roles and use a single PACT
writer per scope. The service account needs the Elasticsearch Security
privileges required to read users/roles, manage PACT roles and user role
assignments, and change native user passwords.

## Testing

Unit tests run as part of the module test suite:

```powershell
mvn -B -pl pact-elasticsearch -am test
```

`ElasticsearchLiveIntegrationTest` is skipped unless
`-Dpact.integration=true` is set. See the root
[integration-test guide](../INTEGRATION_TESTS.md) for its environment and the
disposable Elasticsearch 9 Compose service. The live test uses a generated
native user and index pattern, verifies Secret-based password rotation through
the authenticate API, exercises no index or application data, and cleans up
only those unique test objects.
