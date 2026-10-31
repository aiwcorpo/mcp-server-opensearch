# Katalog narzędzi

Narzędzia to powierzchnia publiczna serwera. Każda zmiana nazwy, opisu, schematu albo ryzyka zmienia
`definitionHash` (C6) i wymaga ponownego zatwierdzenia w gateway'u, więc przechodzi przez code review razem z
tą tabelą. Schematy wejścia powstają z `@ArgumentLimits` na parametrach `OpenSearchTools` i
`SchemaLimitedToolCallbacks.of(...)` ze startera (limity, wzorce, `additionalProperties: false`); wzorzec `index`
pochodzi z definicji `OpenSearchIndexName` kontraktów.

| Narzędzie (klient widzi `opensearch__<nazwa>`) | Ryzyko | Akcja (C6) | Argumenty | Zasób (C7) | Uprawnienie (C9) | Poświadczenie |
| --- | --- | --- | --- | --- | --- | --- |
| `search_logs` ("Szukaj w logach") | `READ_ONLY` | `read` | `index_prefix` (prefiks, wzorzec `OpenSearchIndexPrefix` z kontraktów, 1-255, bez wieloznaczników), `text` (1-512, bez znaków sterujących), `level` (opcjonalnie, `^(?:TRACE\|DEBUG\|INFO\|WARN\|ERROR\|FATAL)$`), `service` (opcjonalnie, do 64 znaków), `from`, `to` (ISO-8601 z przesunięciem, 20-40 znaków; zawężają też indeksy po dacie w nazwie), `size` (1-100, domyślnie 20) | `OpenSearch::Index` w wyniku (`hits[].index`, `@ResourceRef`); zakres z `QueryScope` | `mcp-opensearch-read` + `mcp-opensearch-idx-<prefiks>` | `token-exchange`, `Authorization: Bearer` |
| `aggregate_logs` ("Zlicz logi według pola") | `READ_ONLY` | `read` | `index_prefix`, `group_by` (`level`, `service`, `host`), opcjonalnie `text`, `level`, `service`, `from`, `to`, `size` (1-50, domyślnie 10) | konkretne indeksy z `scope.opensearchSearchTarget` obejmowane prefiksem i ocenione polityką (`ResourcePolicy.allows`); aliasy i strumienie nie są liczone | jak wyżej | jak wyżej |
| `get_document` ("Pobierz dokument z logów") | `READ_ONLY` | `read` | `index` (`^[a-z0-9][a-z0-9._-]{0,254}$`), `id` (`^[A-Za-z0-9_-][A-Za-z0-9_.:@+=-]{0,255}$`) | `OpenSearch::Index` (`index`, `@ResourceRef`) | jak wyżej | jak wyżej |

Błędy (C10):

| Sytuacja | Kod |
| --- | --- |
| zły argument (naruszenie schematu sprawdza starter, resztę narzędzie): prefiks z `,` `:` `<` `-` `_all` `*` `.`, nadmiarowy argument, ponad 100 indeksów po filtrze daty | `invalid_argument` (bez nazwy i wartości argumentu) |
| `get_document`: brak grantów (`QueryScope.nothingToQuery()`), indeks lub dokument nie istnieje, nazwa poza grantami albo prefiksami serwera, indeks z tagiem `restricted`, 403 OpenSearch, alias, data stream, indeks ukryty | `not_found_or_denied` (ten sam tekst) |
| brak grupy `mcp-opensearch-read` | `access_denied` |
| brak poświadczenia, 401 OpenSearch, schemat inny niż `Bearer` | `credential_required` |
| typ poświadczenia spoza `[token-exchange]` (`X-MCP-Credential-Type`) | `internal_error` |
| `search_logs` / `aggregate_logs`: brak grantów, prefiks bez indeksu, poza zakresem, z samymi indeksami `restricted`, alias, data stream | pusty wynik (bez błędu) |
| OpenSearch nieosiągalny, 502/503/504, przekroczony czas | `upstream_unavailable` |
| reszta (np. 500), przekierowanie, odpowiedź nieczytelna albo większa niż `max-response-size` | `internal_error` |

Konwencje: nazwy `snake_case`, czasownik i obiekt, bez prefiksu systemu (dokłada go gateway); opis mówi, co
narzędzie zwraca, bez instrukcji dla modelu. Nazwy argumentów `search_logs` są z briefu ws-04
(`index_prefix`), dlatego parametr metody Java też nazywa się `index_prefix`.
