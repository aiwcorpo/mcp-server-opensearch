# Katalog narzędzi

Narzędzia to powierzchnia publiczna serwera. Każda zmiana nazwy, opisu, schematu albo ryzyka zmienia
`definitionHash` (C6) i wymaga ponownego zatwierdzenia w gateway'u, więc przechodzi przez code review razem z
tą tabelą. Schematy wejścia powstają z `@ArgumentLimits` na parametrach `OpenSearchTools` i
`SchemaLimitedToolCallbacks.of(...)` ze startera (limity, wzorce, `additionalProperties: false`); wzorzec `index`
pochodzi z definicji `OpenSearchIndexName` kontraktów.

| Narzędzie (klient widzi `opensearch__<nazwa>`) | Ryzyko | Akcja (C6) | Argumenty | Zasób (C7) | Uprawnienie (C9) | Poświadczenie |
| --- | --- | --- | --- | --- | --- | --- |
| `search_logs` ("Szukaj w logach") | `READ_ONLY` | `read` | `index_pattern` (`^[a-z0-9-]+\*?$`, 1-255, od prefiksu z grantów), `text` (1-512, bez znaków sterujących), `level` (opcjonalnie, `^(?:TRACE\|DEBUG\|INFO\|WARN\|ERROR\|FATAL)$`), `service` (opcjonalnie, do 64 znaków), `from`, `to` (ISO-8601 z przesunięciem, 20-40 znaków), `size` (1-100, domyślnie 20) | `OpenSearch::Index` w wyniku (`hits[].index`, `@ResourceRef`); zakres z `QueryScope` | `mcp-opensearch-read` + `mcp-opensearch-idx-<prefiks>` | `user-secret` / `token-exchange`, `Authorization: Bearer` lub `Basic` |
| `aggregate_logs` ("Zlicz logi według pola") | `READ_ONLY` | `read` | `index_pattern`, `group_by` (`level`, `service`, `host`), opcjonalnie `text`, `level`, `service`, `from`, `to`, `size` (1-50, domyślnie 10) | indeksy z `_resolve/index` oceniane polityką (`ResourcePolicy.allows`); zakres z `QueryScope` | jak wyżej | jak wyżej |
| `get_document` ("Pobierz dokument z logów") | `READ_ONLY` | `read` | `index` (`^[a-z0-9][a-z0-9._-]{0,254}$`), `id` (`^[A-Za-z0-9_-][A-Za-z0-9_.:@+=-]{0,255}$`) | `OpenSearch::Index` (`index`, `@ResourceRef`) | jak wyżej | jak wyżej |

Błędy (C10):

| Sytuacja | Kod |
| --- | --- |
| zły argument (naruszenie schematu sprawdza starter, resztę narzędzie): wzorzec z `,` `:` `<` `-` `_all` `*` `.`, nadmiarowy argument, data stream w `search_logs`, alias w `aggregate_logs`, agregacja po zbyt wielu indeksach | `invalid_argument` (bez nazwy i wartości argumentu) |
| brak grantów (`QueryScope.nothingToQuery()`), indeks lub dokument nie istnieje, nazwa poza grantami, linkiem albo prefiksami serwera, indeks z tagiem `restricted`, 403 OpenSearch, alias z celem poza prefiksami albo z celem ukrytym, alias kilku indeksów, z filtrem lub routingiem albo data stream w `get_document`, indeks ukryty | `not_found_or_denied` (ten sam tekst) |
| brak grupy `mcp-opensearch-read` | `access_denied` |
| brak poświadczenia, 401 OpenSearch, `service-account`, schemat inny niż `Bearer`/`Basic` | `credential_required` |
| OpenSearch nieosiągalny, 502/503/504, przekroczony czas | `upstream_unavailable` |
| reszta (np. 500), przekierowanie, odpowiedź nieczytelna albo większa niż `max-response-size` | `internal_error` |

Konwencje: nazwy `snake_case`, czasownik i obiekt, bez prefiksu systemu (dokłada go gateway); opis mówi, co
narzędzie zwraca, bez instrukcji dla modelu. Nazwy argumentów `search_logs` są z briefu ws-04
(`index_pattern`), dlatego parametr metody Java też nazywa się `index_pattern`.
