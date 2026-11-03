# Changelog

Wersje SemVer, od najnowszej. Do pierwszego wydania obowiązuje wersja robocza `0.1.0-SNAPSHOT`.

## Nieopublikowane (0.1.0)

- Starter `3.0.0-beta-14` (kontrakty 6.3.2).
- Nowe narzędzia tylko do odczytu: `count_logs`, `list_indices`, `get_index_mapping`, `validate_query`, `get_cluster_health`, `get_backend_info`.
- `search_logs` zwraca `PagedResult` (pole `items` zamiast `hits`) i przyjmuje `cursor`; stronicowanie przez `search_after` z polem
  `MCP_OPENSEARCH_TIEBREAKER_FIELD` (domyślnie wyłączone). Kursor nie powstaje z pozycji trafienia ukrytego przez politykę.
- Sonda wersji (`GET /`): OpenSearch 2.x i 3.x `supported`, inne odpowiedzi `degraded`; `MCP_OPENSEARCH_ASSUME_TESTED_VERSION` dla klastra,
  który odmawia anonimowego `GET /`.
- `list_indices` bez liczby dokumentów i rozmiaru (statystyki ignorują DLS); `get_index_mapping` tylko z polami używanymi przez serwer; `aggregate_logs` zwraca `otherDocuments`.
- Limity czasu: `timeout` ciała 80% i `cancel_after_time_interval` 90% `read-timeout`, budżet `MCP_OPENSEARCH_CALL_BUDGET` dla `search_logs`; `max-response-size` 4 MB.
- Każde narzędzie wołające OpenSearch sprawdza funkcję z sondy; w stanie `unavailable` odmawiają wszystkie.
- Fixtures odpowiedzi OpenSearch 2.19 i 3.x w testach (`opensearch-fixtures/`).
- LICENSE (Apache-2.0), NOTICE, SECURITY.md, CONTRIBUTING.md, metadane licencji w `pom.xml`.
