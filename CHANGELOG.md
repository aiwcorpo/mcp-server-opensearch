# Changelog

Versions follow SemVer, newest first.

## 2.0.0

Major release: the policies of the server live in this repository. Built on `mcp-platform-starter` 4.0.0 and `mcp-contracts` 7.0.0.

- Cedar policies in `mcp-app/src/main/resources/mcp/policies/` (schema in the namespace `OpenSearch`, `resource-types.json`, `read.cedar`,
  `update.cedar`), carried in the manifest with `policiesHash`. They replace `policies/opensearch/` of the `mcp-policies` repository;
  the resource rules are the same, except what the platform now generates for every server: the forbid for indices tagged
  `restricted` and the rule for the service account.
- The call rules by group are now the platform's. `get_cluster_health` and `get_backend_info` keep the ops group as an
  `additionalGroup` of the tool (manifest and `definitionHash`): a caller needs `mcp-opensearch-read` and `mcp-opensearch-ops`.
- A deployed server whose policies or declarations changed waits for approval in the gateway: it refuses calls (`policies_not_approved`,
  `policies_differ`) until an administrator approves the new `policiesHash`. Register the new manifest and approve before the first call.
- The index resolver gives an index the scope of every dash prefix of its name that can be granted (the entity builder did this
  before) and provides the grant probe (`probe(id)`), so a caller without a grant is refused before OpenSearch is asked. The index
  argument takes its pattern from the declared type (`@ArgumentLimits(resourceType = ...)`); the prefix argument publishes the same
  pattern as before, so no `definitionHash` changes because of it.
- Removed: `MCP_POLICY_URL`, `MCP_POLICY_KEY_ID`, `MCP_POLICY_PUBLIC_KEY_FILE`, `MCP_POLICY_STATE_DIR`, the signed policy bundle and the
  `StateDirectory` of the unit. Added: `MCP_GATEWAY_POLICY_DATA_URL` (and `MCP_GATEWAY_TLS_NAME` when the host in it is not a name of the
  gateway certificate). The server connects to the gateway for the policy data with its own certificate, which now needs the usage
  `clientAuth` next to `serverAuth` (CN `mcp-opensearch`, SAN `mcp-opensearch.internal`).
- `deploy/` has `compose.yaml`, `mcp-opensearch.env` and `certs/` next to the systemd files, and the procedure that adds the system
  `opensearch` in the broker and the server `mcp-opensearch` in the gateway without restarting anything.
- `.run/` has IntelliJ configurations for the server and for the `LocalGateway` of `mcp-platform-test`, with the policies directory and a scope mapping.

## 1.0.0 - 2026-11-04

First public release. Built on `mcp-platform-starter` 3.0.0 (contracts 6.3.2).

- Read-only MCP server for OpenSearch 2.x and 3.x clusters that use the Security plugin. Nine tools: `search_logs`,
  `aggregate_logs`, `get_document`, `count_logs`, `list_indices`, `get_index_mapping`, `validate_query`, `get_cluster_health`,
  `get_backend_info`. No tool accepts a query, an API path, SQL or PPL; the server builds every request from structured filters
  (text, level, service, time range, index prefix).
- Calls to OpenSearch carry the calling user's own credential (a token exchanged by the credential broker), so OpenSearch roles,
  DLS and FLS still apply. Index scope comes from the gateway policy; a hit the policy hides never shapes a cursor or a count.
- `search_logs` and `list_indices` return `PagedResult` with an opaque cursor. Paging uses `search_after` with the field
  `MCP_OPENSEARCH_TIEBREAKER_FIELD` (off by default).
- Version probe (`GET /`): OpenSearch 2.x and 3.x are `supported`, other answers are `degraded`. `MCP_OPENSEARCH_ASSUME_TESTED_VERSION`
  covers a cluster that refuses the anonymous `GET /`. When the probe state is `unavailable`, every tool that calls OpenSearch refuses.
- `list_indices` returns no document count or size (index statistics ignore DLS). `get_index_mapping` returns only the fields the
  server uses. `aggregate_logs` returns `otherDocuments`.
- Time limits: the request body `timeout` is 80% and `cancel_after_time_interval` 90% of `read-timeout`; `MCP_OPENSEARCH_CALL_BUDGET`
  bounds `search_logs`; `max-response-size` is 4 MB.
- Tool titles are English, for example "Get OpenSearch version and features" for `get_backend_info`.
- Apache-2.0 licence, NOTICE, SECURITY.md, CONTRIBUTING.md.
