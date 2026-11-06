# mcp-opensearch

MCP server for OpenSearch, one of the native servers of the aiwcorpo MCP platform. It lets an MCP client search logs, count
and aggregate them, and inspect indices of a self-managed OpenSearch cluster that uses the Security plugin. The server only
reads: no tool changes data or cluster settings, and no tool accepts a query, an API path, SQL or PPL from the model. The
server builds every request itself from values the model supplies (text, level, service, time range, index prefix).

It is built on `mcp-platform-starter` 4.0.0 (Java 25, Spring Boot 4.1.0, Spring AI 2.0.0, `mcp-contracts` 7.0.0) and
runs only behind the platform gateway. It accepts traffic over mTLS with the gateway's internal JWT and answers 401 without it;
there is no anonymous mode. Calls to OpenSearch carry the calling user's own credential (a token the credential broker
exchanges for OpenSearch), so OpenSearch Security roles, DLS and FLS still apply.

Platform documentation (gateway, policies, portal, operation) lives in
[aiwcorpo/mcp-docs](https://github.com/aiwcorpo/mcp-docs). This README covers only what belongs to this server.

## Supported OpenSearch versions

| Cluster | State | Tools available |
| --- | --- | --- |
| OpenSearch 2.0 to 2.x (latest checked: 2.19) | `supported` | all |
| OpenSearch 3.x (3.0 to 3.9) | `supported` | all |
| OpenSearch 1.x, Elasticsearch 7.10 and forks without `version.distribution: opensearch`, AWS OpenSearch Serverless | `degraded` | `search_logs`, `aggregate_logs`, `count_logs`, `get_document`, `get_index_mapping`; the others answer `upstream_unavailable` |
| OpenSearch 4.x and later | `degraded` until tested | as above |

At startup and every 5 minutes the server calls `GET /` anonymously and reads `version.number` and `version.distribution`.
The state shows in the log, in the `mcp_backend_capability{probe,state}` metric, in `/actuator/health` and in the
`get_backend_info` tool. Until the first probe answer, and when the probe cannot read a version at all, the state is
`unavailable` and every tool that calls OpenSearch answers `upstream_unavailable`.

A cluster with the Security plugin usually answers the anonymous `GET /` with 401. The server holds no credential of its own,
so the state is `degraded` and `list_indices`, `validate_query` and `get_cluster_health` refuse. If you know the cluster is
OpenSearch 2.x or 3.x, set `MCP_OPENSEARCH_ASSUME_TESTED_VERSION=true`: on a 401 or 403 the probe then reports `supported` with
version `unknown`. The flag records your claim and verifies nothing, so check the version yourself. A response that names
another distribution stays `degraded`.

The test fixtures in `mcp-app/src/test/resources/opensearch-fixtures/` follow the documented response shapes of 2.19 and 3.x. They
were not recorded from a live cluster.

## Tools

Nine tools, all `READ_ONLY` with action `read`. The client sees them as `opensearch__<tool>`.

| Tool | What it does |
| --- | --- |
| `search_logs` | Searches the logs of an index prefix in a time range; pages through `cursor` |
| `aggregate_logs` | Counts documents by `level`, `service` or `host` |
| `count_logs` | Counts documents that match a filter |
| `get_document` | Returns one document by index and id |
| `list_indices` | Lists indices of a prefix with their health (no document counts or sizes: index statistics ignore DLS) |
| `get_index_mapping` | Returns the fields of one index that this server uses, with types |
| `validate_query` | Asks OpenSearch whether it accepts the query the server would build |
| `get_cluster_health` | Cluster status and node and shard counts |
| `get_backend_info` | Version and features from the probe; does not call OpenSearch |

Risk, action and the group a call requires for every tool are in the generated
[tool reference](https://github.com/aiwcorpo/mcp-docs/blob/main/docs/reference/tools.md)
(`docs/reference/tools.md` in `mcp-docs`). The tools that read logs and indices need the group `mcp-opensearch-read`;
`get_cluster_health` and `get_backend_info` describe the whole cluster and need `mcp-opensearch-ops` as well
(`@ToolRisk(additionalGroup = ...)`, in the manifest as `additionalGroup`). Tools that work on indices also need at least one group that the
administrator mapped to an index prefix in the scope mapping (for example `mcp-opensearch-idx-logs-team-a` to
`OpenSearch::Index/logs-team-a`).

Behaviour worth knowing before you deploy:

- An index prefix `p` covers the index `p` and indices `p-...`, never `p...` without the dash. The server resolves the prefix
  against the indices that exist and are in the caller's scope, and sends only concrete index names to `_search`.
- Aliases and data streams are not supported. A cluster that keeps logs in data streams returns empty results.
- A time range removes indices named `p-YYYY.MM.DD` or `p-YYYY.MM` whose date falls outside the range plus one day. Logs that
  arrive more than a day late into such an index can be missed by `count_logs` and `aggregate_logs`.
- `search_logs` pages with `search_after`. This needs `MCP_OPENSEARCH_TIEBREAKER_FIELD`, a field with a unique value in every
  document (not `_id`). Without it the tool returns the first page only.
- Cursors are encrypted and bound to the user, the tool and the query. Every instance needs the same `MCP_CURSOR_KEY`.
- Values from OpenSearch (field names and contents) reach the model as `UntrustedText`, never as instructions.

## Policies

The Cedar policies of OpenSearch live here, in `mcp-app/src/main/resources/mcp/policies/`, and are part of the jar and of the manifest:

| File | Content |
| --- | --- |
| `schema.cedarschema` | namespace `OpenSearch`: `OpenSearch::Index` (with the classification attribute `tags`), actions `read`, `update`, `delete`, `admin` |
| `resource-types.json` | the declaration of the type: `idPattern` (an index name), `scopePattern` (a grantable prefix) and the classification |
| `read.cedar` | reading needs `mcp-opensearch-read` and a scope that covers the index |
| `update.cedar` | rules for `update` and `delete` inside a granted prefix; no current tool uses them |

What is not written here is generated by the platform: the group a tool needs, the closed `restricted` indices (an index with the
tag `restricted` from `opensearch.scope.index-tags`) and the limits of the service account. Who holds which prefix is administrator
data in the gateway (the scope mapping), not part of this repository. The resolver gives an index the scope of every dash prefix of
its name that can be granted (`logs-team-a-2026.10` lies in `logs`, `logs-team`, `logs-team-a` and itself), so a grant of
`logs-team-a` reaches it and one of `logs-team-ab` does not; `QueryScope` builds the index expression from the same grants.
`OpenSearchPolicyDecisionsTest` states the decisions with `PolicyDecisions`; a change to any file here changes `policiesHash` in the
manifest, and an administrator has to approve it before the server decides anything.

## Running against a real cluster

`.run/` has two IntelliJ configurations. Start `LocalGateway` (the gateway of `mcp-platform-test` on `http://127.0.0.1:18500/mcp`,
serving the JWKS and the policy data on port 18445 with the policies of this working tree and the mapping in
`.run/scope-mapping.json`), then `McpOpenSearch (dev, behind LocalGateway)` with `MCP_OPENSEARCH_URL` and
`MCP_OPENSEARCH_INDEX_PREFIXES` set. Connect an MCP client to the gateway with `Authorization: Bearer <your token>`. Edit
`.run/scope-mapping.json` and the `--groups` of the `LocalGateway` configuration for your prefixes, and restart `LocalGateway`
after a change to the policies (it approves the hash of the files it read). The configurations need the `4.0.0` artifacts in
`~/.m2` (`./mvnw verify` of the starter).

## Build and test

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"   # macOS; on Linux the JDK 25 directory
./mvnw verify        # jar: mcp-app/target/mcp-opensearch.jar and mcp-app/target/mcp-manifest.json
make ci              # ci/run.sh verify: text checks, clean verify, required test counts, manifest present
```

Tests need no Docker and no platform environment. `TestGateway` from `mcp-platform-test` signs internal JWTs, and `StubBackend`
imitates the OpenSearch REST API, including aliases, data streams, hidden indices and broken responses. It is not a real cluster.

Platform artifacts (`mcp-platform-bom`, contracts) come from Maven Central or from `~/.m2`;
the setup is described in the README of `mcp-starter-lib`. Linux and macOS with bash 3.2 or newer; Windows only through WSL2.

The build writes `mcp-app/target/mcp-manifest.json`. That file, not the answer of a running server, is what CI registers with
the gateway. Any change to a tool changes its `definitionHash` and needs a new approval in the gateway.

Modules: `mcp-core` (domain and ports), `mcp-application` (use cases), `mcp-integration` (OpenSearch adapter, version probe),
`mcp-tools` (MCP tools), `mcp-app` (bootstrap, the single jar, end-to-end tests).

## Configuration

`application.yml` is the production configuration: mTLS, JWKS of the gateway over TLS, `jti` store in Redis, `https` to
OpenSearch. The server does not start without these:

| Variable | Meaning |
| --- | --- |
| `MCP_TLS_KEYSTORE`, `MCP_TLS_TRUSTSTORE`, `MCP_TLS_STORE_PASSWORD` | PKCS12 files: server certificate (CN `mcp-opensearch`, SAN `mcp-opensearch.internal`, server and client use) and the internal CA |
| `MCP_GATEWAY_JWKS_URI` | JWKS of the gateway's internal JWT |
| `MCP_CONNECT_URL` | Portal page for connecting an account, with `{system}`; sent in `credential_required` |
| `MCP_OPENSEARCH_URL` | REST address of OpenSearch (`https` outside the `dev` profile) |
| `MCP_OPENSEARCH_INDEX_PREFIXES` | Upper bound of index prefixes, comma-separated; a grant outside this list gives nothing |
| `MCP_GATEWAY_POLICY_DATA_URL` | Policy data of the gateway (`/internal/v1/policy-data`), `https` only, fetched with this server's own certificate |
| `MCP_INTERNAL_URL` | Address of this server in the manifest |
| `MCP_REDIS_HOST`, `MCP_REDIS_PORT`, `MCP_REDIS_USER`, `MCP_REDIS_PASSWORD` | Redis for single-use `jti` (ACL user `mcp-opensearch`) |

Optional settings: `MCP_OPENSEARCH_TIMESTAMP_FIELD` (`@timestamp`), `MCP_OPENSEARCH_QUERY_FIELDS` (`message`),
`MCP_OPENSEARCH_LEVEL_FIELD`, `MCP_OPENSEARCH_SERVICE_FIELD`, `MCP_OPENSEARCH_HOST_FIELD`, `MCP_OPENSEARCH_RESULT_FIELDS`,
`MCP_OPENSEARCH_MAX_RANGE` (`7d`), `MCP_OPENSEARCH_TIEBREAKER_FIELD`, `MCP_OPENSEARCH_TIEBREAKER_TYPE`,
`MCP_OPENSEARCH_CALL_BUDGET` (`20s`), `MCP_OPENSEARCH_SSL_BUNDLE`, `MCP_OPENSEARCH_ASSUME_TESTED_VERSION`, `MCP_CURSOR_KEY`,
`MCP_GATEWAY_TLS_NAME` (only when the host in the policy data address is not a name of the gateway certificate), `MCP_PORT` (`8443`) and `MCP_BIND`. Defaults, property names and the fixed timeouts are in the
[server environment reference](https://github.com/aiwcorpo/mcp-docs/blob/main/docs/reference/env-servers.md)
(`docs/reference/env-servers.md` in `mcp-docs`). The MCP endpoint is `/mcp`; without a JWT only `/actuator/health` answers.

What the cluster needs: the Security plugin with JWT or OpenID Connect authentication that accepts the broker's token and maps
the user's backend roles to OpenSearch roles. The role needs `indices:admin/resolve/index` plus the read actions of the tools
in use (`indices:data/read/search*`, `indices:data/read/get`, `indices:admin/mappings/get`, `indices:admin/validate/query`,
and for `list_indices` and `get_cluster_health` the monitor actions). Names differ between plugin releases, so confirm them on
the first run. `do_not_fail_on_forbidden: true` in the role makes a list that includes one forbidden index return the rest.

## Install

The server is one jar or one image. For a VM with systemd use `deploy/scripts/install.sh mcp-app/target/mcp-opensearch.jar` and the
environment template `deploy/systemd/mcp-opensearch.env.template`; the [VM installation guide](https://github.com/aiwcorpo/mcp-docs/blob/main/docs/operator/install-vm.md) in `mcp-docs` has the steps. For a
container run `docker build -t mcp-opensearch:<tag> .` after `./mvnw verify` (image `eclipse-temurin:25-jre`, user `mcp`,
port 8443). Operating notes, upgrades and troubleshooting are in `mcp-docs`.

## Run locally without the platform

The `dev` profile must be the only active profile. It serves plain HTTP on 127.0.0.1, keeps `jti` in memory and needs no
certificates. There is no gateway, so it only shows that the server starts and rejects requests without a JWT:

```bash
SPRING_PROFILES_ACTIVE=dev MCP_PORT=18541 "$JAVA_HOME/bin/java" -jar mcp-app/target/mcp-opensearch.jar
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18541/actuator/health    # 200
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' \
     -H 'Accept: application/json, text/event-stream' \
     -d '{"jsonrpc":"2.0","id":1,"method":"ping"}' http://127.0.0.1:18541/mcp       # 401
```

The `dev` profile accepts JWTs signed with the published test keys of the contracts and needs no client certificate. For that
reason it listens on loopback only, and the starter refuses to start `dev` on another address. Never enable it on a host or in a
container that the gateway connects to, and never put `SPRING_PROFILES_ACTIVE=dev` into an `.env` file, a systemd unit or an
image. The local dev stack is not public yet; to run the platform with mTLS use the Compose install in `mcp-docs` (`examples/compose`).

## Documentation

The guides for developers, operators and administrators are in [`mcp-docs`](https://github.com/aiwcorpo/mcp-docs). Deployment files: `deploy/`, `Dockerfile`.

Vulnerability reports: `SECURITY.md`. Contributions: `CONTRIBUTING.md`. Changes: `CHANGELOG.md`.

## License

Apache-2.0, see `LICENSE`.
