# Changelog

Versions follow SemVer, newest first.

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
