# Changelog

Versions follow SemVer, newest first. Until the first release the working version is `0.1.0-SNAPSHOT`.

## Unreleased (0.1.0)

- Starter `3.0.0-beta-14` (contracts 6.3.2).
- New read-only tools: `count_logs`, `list_indices`, `get_index_mapping`, `validate_query`, `get_cluster_health`, `get_backend_info`.
- `search_logs` returns `PagedResult` (field `items` instead of `hits`) and accepts `cursor`. Paging uses `search_after` with the field
  `MCP_OPENSEARCH_TIEBREAKER_FIELD` (off by default). A cursor is never built from the position of a hit the policy hides.
- Version probe (`GET /`): OpenSearch 2.x and 3.x are `supported`, other answers are `degraded`. `MCP_OPENSEARCH_ASSUME_TESTED_VERSION`
  covers a cluster that refuses the anonymous `GET /`.
- `list_indices` returns no document count or size (index statistics ignore DLS). `get_index_mapping` returns only the fields the server
  uses. `aggregate_logs` returns `otherDocuments`.
- Time limits: the request body `timeout` is 80% and `cancel_after_time_interval` 90% of `read-timeout`; `MCP_OPENSEARCH_CALL_BUDGET`
  bounds `search_logs`; `max-response-size` is 4 MB.
- Every tool that calls OpenSearch checks the feature reported by the probe; in the `unavailable` state all of them refuse.
- Fixtures of OpenSearch 2.19 and 3.x responses in the tests (`opensearch-fixtures/`).
- LICENSE (Apache-2.0), NOTICE, SECURITY.md, CONTRIBUTING.md, licence metadata in `pom.xml`.
