# STATUS - mcp-server-opensearch (ws-04)

M1: osiągnięty (2026-10-27) - `search_logs` i `get_document` (READ_ONLY) na starterze 3.0.0-alpha-5, jeden wzorzec indeksu od prefiksu z konfiguracji, aliasy rozwiązywane i sprawdzane, poświadczenie z C3, 32 testy na stubie OpenSearch 2.x.

Starter: BOM `pl.aiwcorpo.mcp:mcp-platform-bom:3.0.0-alpha-5` (kontrakty 2.0.2, Spring Boot 4.1.0, Spring AI
2.0.0, Java 25). Repo wygenerowane `new-server.sh opensearch` z template'u (pierwszy commit), M2 nie jest zaczęty.

## Co działa

- `server-id` `mcp-opensearch`, endpoint `/mcp`, konfiguracja produkcyjna z template'u (mTLS `need`, JWKS po
  https z CA wewnętrznym, `jti` w Redisie). Smoke na hoście z `mcp-platform-dev` (port 18540, certyfikat
  `mcp-opensearch`, Redis 16379, użytkownik `mcp-opensearch`): health po mTLS 200, `POST /mcp` bez JWT 401, bez
  certyfikatu klienta handshake zerwany, bez `MCP_OPENSEARCH_INDEX_PREFIXES` brak startu. Proces zatrzymany.
- Narzędzia (szczegóły: README, `docs/api/tools-catalog.md`):
  - `search_logs(index_pattern, query, from, to, size<=100)`, `READ_ONLY`;
  - `get_document(index, id)`, `READ_ONLY`.
  Oba z `@ToolRisk`, opisem bez instrukcji dla modelu, ręcznym `inputSchema` (`additionalProperties: false`,
  `maxLength`, `pattern`, `minimum`/`maximum`), rejestracja tylko przez `ToolCallbackProvider` w
  `McpToolsConfiguration` (callbacki dalej `MethodToolCallback`, z których starter czyta `@ToolRisk`).
- `index_pattern`: dokładnie jeden wzorzec `^[a-z0-9-]+\*?$`, odrzucane `,`, `:`, `<`, wykluczenia `-`, `_all`,
  sam `*`, indeksy `.xxx`; prefiks z konfiguracji na granicy `-`; `p*` -> `p,p-*` (zgodnie z
  `entity-builder.md` sekcja 8). Potem `_resolve/index` i kontrola każdego indeksu i celu aliasu; potem
  wyszukiwanie z filtrem `_index` w zapytaniu i odrzucenie trafień spoza zakresu.
- `get_document`: te same kontrole dla konkretnej nazwy; alias jednego indeksu czytany przez ten indeks.
- `query` tylko jako tekst `simple_query_string` po polach z konfiguracji (bez FUZZY/NEAR/SLOP); zakres czasu
  obowiązkowy, `to > from`, najwyżej `MCP_OPENSEARCH_MAX_RANGE` (7 dni); bez `_msearch`, `_mget`, skryptów,
  `?q=`, agregacji, liczby wszystkich trafień.
- Wynik: tylko pola z `MCP_OPENSEARCH_RESULT_FIELDS` (jako `_source.includes` i ponownie w adapterze), treść w
  `data` z `dataNote`, `<`, `>`, `&` escapowane, limity długości i rozmiaru.
- Błędy: 404 i 403 -> `not_found_or_denied` (ten sam tekst, także alias poza zakres, indeks ukryty, brakujący
  indeks nazwany wprost); brak poświadczenia i 401 -> `credential_required`; treść błędu OpenSearch nie trafia do
  wyniku ani logu (obejście `defaultStatusHandler` z template'u zostało).
- Poświadczenie: `user-secret` i `token-exchange`, schemat `Bearer` albo `Basic`, wszystko przez `applyTo`.
  `service-account`, `user-oauth` i schematy `Token`/`ApiKey` -> `credential_required` (fail-closed: konto
  techniczne zastąpiłoby DLS/FLS użytkownika). DLS/FLS jako druga warstwa: README.
- Testy (`./mvnw verify`, JDK 25, bez Dockera): 32, 0 błędów.
  - `SearchLogsToolTest` (15), `GetDocumentToolTest` (9), `GatewayOnlyAccessTest` (3, na `get_document`),
    `OpenSearchServiceTest` (5).
  - Dla każdego narzędzia: pozytywny, `credential_required` (brak i 401), `not_found_or_denied`,
    `invalid_argument`, negatywne dla każdego identyfikatora (`index_pattern`, `index`, `id`).

## Jedno miejsce na QueryScope (M3)

`IndexScopeProvider` (port w `mcp-core`) to jedyne źródło zakresu. M1: `ConfiguredIndexScopeProvider` zwraca
prefiksy z `MCP_OPENSEARCH_INDEX_PREFIXES` każdemu wywołującemu. Ze starterem beta implementacja zwróci
przecięcie tej listy z `QueryScope` wywołującego (`granted` z `queryConstraints["OpenSearch::Index"]`, z
uwzględnieniem `vsc`); pusty wynik -> pusta lista bez zapytania (`entity-builder.md` sekcja 8). Reszta
(`OpenSearchService`, filtr `_index`, odrzucanie trafień) już działa na `IndexScope`. `ResultFilter` wepnie się
w `OpenSearchService.searchLogs` w miejscu `filter(hit -> scope.covers(hit.index()))`.

`@ResourceRef` (M3): tylko `get_document.index` (`OpenSearch::Index`). `search_logs.index_pattern` z `*` nie
pasuje do `OpenSearchIndexName` kontraktu, więc nie może być `@ResourceRef`; zakres wyszukiwania pochodzi z
`QueryScope` (tak jak w wektorze `result-filter/opensearch-search-without-link`). Brief ws-04 mówi
"`@ResourceRef` dla każdego identyfikatora" - do potwierdzenia przy M3.

## Odstępstwa i decyzje fail-closed

- Kontraktowy `OpenSearchIndexName` przyjmuje wiodącą kropkę; serwer ją odrzuca (argumenty i konfiguracja).
  Propozycja CC niżej.
- `index_pattern` wg briefu nie dopuszcza `.`, więc indeksu `logs-team-a-2026.10.27` nie da się nazwać w
  `search_logs` dosłownie; działa `logs-team-a-2026*`. `get_document` przyjmuje kropki.
- Strumienie danych (data streams) -> `invalid_argument`: ich indeksy zapasowe `.ds-*` są ukryte i nie mają
  prefiksu; obsługa (zasób = nazwa strumienia) do decyzji w M2.
- Brakujący indeks nazwany wprost w `search_logs` -> `not_found_or_denied` (jak 403); wzorzec bez dopasowań ->
  pusty wynik.
- Agregacje: brak (H2 z recenzji kontraktów 2.0.0, odłożone do decyzji człowieka).
- `Basic` przyjmowany technicznie (zadanie koordynatora), choć decyzja człowieka 2026-10-27 mówi, że
  `user-secret` to pojedynczy token, a Basic dla użytkowników w v1 nie jest obsługiwany. Broker go więc nie
  wyda; serwer nic na tym nie traci.
- Nazwa argumentu `index_pattern` (snake_case z briefu) wymusza taką nazwę parametru metody Java (Spring AI wiąże
  argumenty po nazwach parametrów).
- Prawdziwego OpenSearch nie uruchamiałem (Testcontainers wymagałby pobrania obrazu ~1 GB, testy przestałyby
  być szybkie). Kształty odpowiedzi w `StubBackend` są z dokumentacji API 2.x; niepotwierdzone na klastrze:
  `ignore_unavailable`/`allow_no_indices` w `_resolve/index` i zapytanie `prefix` na polu `_index`.

## Znane ograniczenia

- Uprawnienia użytkownika w OpenSearch muszą obejmować `indices:admin/resolve/index`; bez tego każde wywołanie
  daje `not_found_or_denied`. Zalecane `do_not_fail_on_forbidden: true` (README).
- Argumenty odrzucone przez schemat dostaje klient z tekstem SDK MCP, nie jako C10 (niżej, ws-02).

## Czego potrzebują inni

- ws-01: w `mcp-platform-dev` nie ma mocka OpenSearch. Potrzebny WireMock (albo kontener OpenSearch 2.x z
  Security) z: `GET /_resolve/index/{expr}`, `POST /{expr}/_search`, `GET /{index}/_doc/{id}`,
  `GET /_plugins/_security/authinfo` (whoami brokera, C4.1), tokenem użytkownika `alice` (Bearer), 401 dla
  złego tokenu, 403 dla indeksu bez uprawnień, aliasem prowadzącym poza prefiks i indeksem ukrytym (wzór:
  `mcp-app/src/test/java/.../StubBackend.java`). Do tego `MCP_OPENSEARCH_INDEX_PREFIXES` i `MCP_CONNECT_URL`
  w usłudze `mcp-opensearch` w compose.
- ws-02 (starter):
  1. SDK MCP waliduje argumenty względem `inputSchema` przed `AuditingToolCallback`: naruszenie schematu daje
     wynik z tekstem `Tool (x) input validation failed: ...` (nie C10 `invalid_argument`) i nie daje zdarzenia
     C8 `tool_call` (sprawdzone w logu testów). Propozycja: starter mapuje ten błąd na C10 i audytuje go, albo
     wyłącza walidację SDK i waliduje schemat sam w opakowaniu.
  2. Brak wsparcia dla schematu wejścia z limitami: `@ToolParam` nie ma `maxLength`/`pattern`, więc serwer
     buduje `MethodToolCallback` z ręcznym `ToolDefinition`. Wygodny byłby helper startera.
  3. `ToolCallInterceptor` nie dostaje definicji narzędzia (schematu), więc kontrola nadmiarowych argumentów
     trzyma listę nazw osobno (`OpenSearchToolSchemas`).
- ws-03 (template): wzorzec ręcznego `inputSchema` (`McpToolsConfiguration`) i `StrictArgumentsInterceptor`
  może wejść do template'u; `GatewayOnlyAccessTest` ma stałą `GET_RECORD`, którą każdy serwer przerabia.
- ws-05 (gateway): nic nowego ponad README gateway'a.

## Propozycje zmian kontraktu

- CC-??? (C7): `OpenSearchIndexName`/`OpenSearchIndexPrefix` bez wiodącej kropki; dopisane na końcu
  `mcp-contracts/CONTRACT_CHANGES.md`, status open.
- Oznaczenie treści z systemu: ws-04 (confluence) zgłosił już CC na wspólny kształt `UntrustedText`; ten serwer
  używa tymczasowo `data` + `dataNote` i przejdzie na wspólny kształt po decyzji.
