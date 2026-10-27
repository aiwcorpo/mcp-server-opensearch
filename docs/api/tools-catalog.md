# Katalog narzędzi

Narzędzia to powierzchnia publiczna serwera. Każda zmiana nazwy, opisu, schematu albo ryzyka zmienia
`definitionHash` (C6) i wymaga ponownego zatwierdzenia w gateway'u, więc przechodzi przez code review razem z
tą tabelą. Schematy wejścia są pisane ręcznie w `OpenSearchToolSchemas` (limity, wzorce,
`additionalProperties: false`).

| Narzędzie (klient widzi `opensearch__<nazwa>`) | Ryzyko | Akcja (C6) | Argumenty | Zasób (C7) | Uprawnienie (C9) | Poświadczenie |
| --- | --- | --- | --- | --- | --- | --- |
| `search_logs` | `READ_ONLY` | `read` | `index_pattern` (`^[a-z0-9-]+\*?$`, 1-255, bez wiodącego `-`, od dozwolonego prefiksu), `query` (1-512, bez znaków sterujących), `from`, `to` (ISO-8601 z przesunięciem, 20-40 znaków), `size` (1-100, domyślnie 20) | `OpenSearch::Index` (`index_pattern`, od M3 przez `@ResourceRef`) | `mcp-opensearch-read`, zakres `mcp-opensearch-idx-<prefiks>` | `user-secret` (M1) / `token-exchange` (docelowo), `Authorization: Bearer` lub `Basic` |
| `get_document` | `READ_ONLY` | `read` | `index` (`^[a-z0-9][a-z0-9._-]{0,254}$`, od dozwolonego prefiksu), `id` (`^[A-Za-z0-9_-][A-Za-z0-9_.:@+=-]{0,255}$`) | `OpenSearch::Index` (`index`) | jak wyżej | jak wyżej |

Błędy (C10):

| Sytuacja | Kod |
| --- | --- |
| zły argument, wzorzec z `,` `:` `<` `-` `_all` `*` `.`, prefiks spoza konfiguracji, data stream, alias kilku indeksów w `get_document` | `invalid_argument` (dla naruszeń schematu tekst SDK MCP, "input validation failed") |
| indeks lub dokument nie istnieje, 403 OpenSearch, alias z celem poza prefiksami, indeks ukryty | `not_found_or_denied` (ten sam tekst) |
| brak poświadczenia, 401 OpenSearch, `service-account`, schemat inny niż `Bearer`/`Basic` | `credential_required` |
| OpenSearch nieosiągalny, 502/503/504 | `upstream_unavailable` |
| reszta (np. 500) | `internal_error` |

Konwencje: nazwy `snake_case`, czasownik i obiekt, bez prefiksu systemu (dokłada go gateway); opis mówi, co
narzędzie zwraca, bez instrukcji dla modelu. Nazwy argumentów `search_logs` są z briefu ws-04
(`index_pattern`), dlatego parametr metody Java też nazywa się `index_pattern`.
