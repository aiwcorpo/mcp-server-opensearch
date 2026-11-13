# mcp-opensearch

Part of [MCP Platform](https://github.com/aiwcorpo/mcp-platform), a self-hosted MCP gateway for companies. Documentation: [mcp.aiwcorpo.pl](https://mcp.aiwcorpo.pl/).

MCP server for a self-managed OpenSearch cluster. AI clients such as Claude Code reach it through the gateway of the aiwcorpo MCP
platform to search and count logs and inspect indices; the server calls OpenSearch with each user's own token.

The server only reads. No tool changes data or settings, and no tool accepts a query, an API path, SQL or PPL: the server builds
every request itself from the values the client passes (text, level, service, time range, index prefix).

## Quick start

```bash
cd deploy
# fill in MCP_GATEWAY_JWKS_URI, MCP_CONNECT_URL, MCP_OPENSEARCH_URL and MCP_OPENSEARCH_INDEX_PREFIXES in mcp-opensearch.env
docker compose up -d
docker compose logs mcp-opensearch | grep event=tls_certificate
```

Then the platform operator adds `https://<this host>:8443/mcp` in the portal. Full steps: [deploy/README.md](deploy/README.md).

## Tools

11 tools, shown to a client as `opensearch__<name>`. All are read-only. They need the group `mcp-opensearch-read` and at least one
group that the operator mapped to an index prefix. The tools marked "ops" need the group `mcp-opensearch-ops` as well.

| Area | Tool | What it does |
| --- | --- | --- |
| Logs | `search_logs` | Searches the logs of an index prefix in a time range; pages with `cursor` |
| | `count_logs` | Counts documents that match a filter |
| | `aggregate_logs` | Counts documents by `level`, `service` or `host` |
| | `find_log_patterns` | Groups the messages of a sample (up to 500, newest first) into patterns with counts and one example each |
| | `get_document` | Returns one document by index and id |
| Indices | `list_indices` | Lists the indices of a prefix with their health |
| | `get_index_mapping` | Returns the fields of one index that the server uses, with types |
| | `validate_query` | Asks OpenSearch whether it accepts the query the server would build |
| | `get_index_stats` (ops) | Document count, store size and indexing and search totals per index; the numbers ignore DLS, hence the ops group |
| Cluster | `get_cluster_health` (ops) | Status, node and shard counts, pending tasks |
| | `get_backend_info` (ops) | Version and features found by the version probe; does not call OpenSearch |

An index prefix `p` covers the index `p` and the indices `p-...`, never `p...` without the dash. Aliases and data streams are not
supported: a cluster that keeps logs in data streams returns empty results. Text from OpenSearch reaches the model as untrusted
text, never as instructions.

`search_logs` pages only when `MCP_OPENSEARCH_TIEBREAKER_FIELD` names a field with a unique value in every document (not `_id`).
Without it the tool returns the first page.

## Supported OpenSearch versions

| Cluster | State | Tools available |
| --- | --- | --- |
| OpenSearch 2.0 to 2.x (latest checked: 2.19) | `supported` | all |
| OpenSearch 3.x (3.0 to 3.9) | `supported` | all |
| OpenSearch 1.x, Elasticsearch 7.10 and forks without `version.distribution: opensearch`, AWS OpenSearch Serverless | `degraded` | `search_logs`, `aggregate_logs`, `count_logs`, `get_document`, `get_index_mapping`; the others answer `upstream_unavailable` |
| OpenSearch 4.x and later | `degraded` until tested | as above |

At startup and every 5 minutes the server calls `GET /` without a credential and reads the version. A cluster with the Security
plugin usually answers 401, so the state is `degraded`. If the cluster is OpenSearch 2.x or 3.x, set
`MCP_OPENSEARCH_ASSUME_TESTED_VERSION=true`; the server then reports `supported` with version `unknown` and verifies nothing.
The state shows in the log, in `/actuator/health` and in `get_backend_info`.

## Configuration

Required, in `deploy/mcp-opensearch.env`:

| Variable | Description |
| --- | --- |
| `MCP_GATEWAY_JWKS_URI` | key set of the gateway: `https://<platform>/.well-known/mcp-jwks.json` |
| `MCP_CONNECT_URL` | portal page where a user connects an account: `https://<platform>/connections?system={system}` |
| `MCP_OPENSEARCH_URL` | REST address of OpenSearch, `https` |
| `MCP_OPENSEARCH_INDEX_PREFIXES` | index prefixes the server may touch, comma-separated; a grant outside this list gives nothing |

Optional settings:

| Variable | Default | Description |
| --- | --- | --- |
| `MCP_OPENSEARCH_TIMESTAMP_FIELD` | `@timestamp` | time field of the logs |
| `MCP_OPENSEARCH_QUERY_FIELDS` | `message` | fields searched by `search_logs` text |
| `MCP_OPENSEARCH_LEVEL_FIELD`, `_SERVICE_FIELD`, `_HOST_FIELD` | `log.level`, `service.name`, `host.name` | fields used for filters and `aggregate_logs` |
| `MCP_OPENSEARCH_RESULT_FIELDS` | `@timestamp,message,log.level,service.name,host.name` | fields returned for a hit |
| `MCP_OPENSEARCH_MAX_RANGE` | `7d` | longest time range of one call |
| `MCP_OPENSEARCH_TIEBREAKER_FIELD`, `_TIEBREAKER_TYPE` | empty, `keyword` | paging of `search_logs` |
| `MCP_OPENSEARCH_CALL_BUDGET` | `20s` | time one `search_logs` call may spend |
| `MCP_OPENSEARCH_SSL_BUNDLE` | empty | trust store for OpenSearch, if it needs a non-default one |
| `MCP_OPENSEARCH_ASSUME_TESTED_VERSION` | `false` | see the versions section |
| `MCP_PORT`, `MCP_BIND` | `8443`, `0.0.0.0` | listen address |

Own certificate (`MCP_TLS_*`), Redis and the cursor key for several instances are commented in the same file and described in
[deploy/README.md](deploy/README.md).

## Build

```bash
export JAVA_HOME=<path to JDK 25>
./mvnw verify
```

The jar is `mcp-app/target/mcp-opensearch.jar`; `docker build -t mcp-opensearch:dev .` builds the image. Tests need neither Docker
nor OpenSearch. Working on the code: [CONTRIBUTING.md](CONTRIBUTING.md).

## More

- [deploy/README.md](deploy/README.md): installation, the OpenSearch role, troubleshooting, upgrade from 1.x
- [CHANGELOG.md](CHANGELOG.md), [SECURITY.md](SECURITY.md)

Apache License 2.0.
