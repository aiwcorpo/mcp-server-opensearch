# STATUS - mcp-server-opensearch (ws-04)

M1: osiągnięty (2026-10-27, runda 2) - `search_logs` i `get_document` (READ_ONLY) na starterze 3.0.0-alpha-6, walidacja argumentów przez starter (C10), jeden wzorzec indeksu od prefiksu z konfiguracji, aliasy rozwiązywane i sprawdzane (cele ukryte, filtr), poświadczenie z C3, 56 testów na stubie OpenSearch 2.x.

Starter: BOM `pl.aiwcorpo.mcp:mcp-platform-bom:3.0.0-alpha-6` (kontrakty 2.0.2, Spring Boot 4.1.0, Spring AI
2.0.0, Java 25). Repo wygenerowane `new-server.sh opensearch` z template'u 7f65f8f (pierwszy commit); zmiany template'u do c1258c4
przeniesione w rundzie 2. M2 nie jest zaczęty.

## Co działa

- `server-id` `mcp-opensearch`, endpoint `/mcp`, konfiguracja produkcyjna z template'u (mTLS `need`, JWKS po
  https z CA wewnętrznym, `jti` w Redisie). Smoke na hoście z `mcp-platform-dev` (port 18540, certyfikat
  `mcp-opensearch`, Redis 16379, użytkownik `mcp-opensearch`): health po mTLS 200, `POST /mcp` bez JWT 401, bez
  certyfikatu klienta handshake zerwany, bez `MCP_OPENSEARCH_INDEX_PREFIXES` brak startu. Runda 2 powtórzyła to
  na jarze alpha-6 (port 18540, `MCP_OPENSEARCH_ALLOW_PLAIN_HTTP=true` bo mock Opensearch nie istnieje) oraz w
  trybie `dev` (port 18541: health 200, `POST /mcp` 401, `/actuator/prometheus` 401). Procesy zatrzymane.
- Narzędzia (szczegóły: README, `docs/api/tools-catalog.md`):
  - `search_logs(index_pattern, query, from, to, size<=100)`, `READ_ONLY`;
  - `get_document(index, id)`, `READ_ONLY`.
  Oba z `@ToolRisk`, opisem bez instrukcji dla modelu, limitami przez `@ArgumentLimits` (wzorzec `index` z
  definicji `OpenSearchIndexName` kontraktów) i `SchemaLimitedToolCallbacks.of(...)`, rejestracja tylko przez
  `ToolCallbackProvider` w `McpToolsConfiguration`. Schemat egzekwuje starter (C10 `invalid_argument` + C8).
- `index_pattern`: dokładnie jeden wzorzec `^[a-z0-9-]+\*?$`, odrzucane `,`, `:`, `<`, wykluczenia `-`, `_all`,
  sam `*`, indeksy `.xxx`; prefiks z konfiguracji na granicy `-`; `p*` -> `p,p-*` (zgodnie z
  `entity-builder.md` sekcja 8). Potem `_resolve/index` i kontrola każdego indeksu i celu aliasu; potem
  wyszukiwanie z filtrem `_index` w zapytaniu i odrzucenie trafień spoza zakresu.
- `get_document`: te same kontrole dla konkretnej nazwy; alias jednego indeksu czytany przez ten indeks, o ile
  nie ma filtra ani routingu (`GET _alias/<nazwa>`), bo odczyt po id by je ominął.
- Cele aliasów sprawdzane drugim `_resolve/index` po nazwie (atrybut `hidden`); poza zakresem poprawna nazwa
  daje `not_found_or_denied`, jak brakujący indeks.
- `query` tylko jako tekst `simple_query_string` po polach z konfiguracji (bez FUZZY/NEAR/SLOP); zakres czasu
  obowiązkowy, `to > from`, najwyżej `MCP_OPENSEARCH_MAX_RANGE` (7 dni); bez `_msearch`, `_mget`, skryptów,
  `?q=`, agregacji, liczby wszystkich trafień.
- Wynik: tylko pola z `MCP_OPENSEARCH_RESULT_FIELDS` (jako `_source.includes` i ponownie w adapterze), każdy
  napis w `data` jako `UntrustedText` (CC-072), `<`, `>`, `&` escapowane, limity długości i rozmiaru.
- Adapter jak w template'ie: `https` poza `dev` (wyjątek `MCP_OPENSEARCH_ALLOW_PLAIN_HTTP`), bez userinfo/query w
  adresie, bez przekierowań (3xx to `internal_error`, nie "brak"), `max-response-size` 1 MB,
  `BackendResponseException` bez treści, prometheus niewystawiony.
- Błędy: 404 i 403 -> `not_found_or_denied` (ten sam tekst, także alias poza zakres, indeks ukryty, brakujący
  indeks nazwany wprost); brak poświadczenia i 401 -> `credential_required`; treść błędu OpenSearch nie trafia do
  wyniku ani logu (obejście `defaultStatusHandler` z template'u zostało).
- Poświadczenie: `user-secret` i `token-exchange`, schemat `Bearer` albo `Basic`, wszystko przez `applyTo`.
  `service-account`, `user-oauth` i schematy `Token`/`ApiKey` -> `credential_required` (fail-closed: konto
  techniczne zastąpiłoby DLS/FLS użytkownika). DLS/FLS jako druga warstwa: README.
- Testy (`./mvnw clean verify`, JDK 25, bez Dockera): 56, 0 błędów.
  - `SearchLogsToolTest` (21), `GetDocumentToolTest` (18), `GatewayOnlyAccessTest` (4, na `get_document`),
    `ProductionConfigurationTest` (4), `DevProfileLoopbackTest` (1), `OpenSearchServiceTest` (8). Trzy klasy
    `mcp-app` dziedziczą `contractSystemIsKnown`, więc liczą się w ich sumach.
  - Dla każdego narzędzia: pozytywny, `credential_required` (brak i 401), `not_found_or_denied`,
    `invalid_argument` (C10), negatywne dla każdego identyfikatora (`index_pattern`, `index`, `id`).

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
- Strumienie danych (data streams) i alias wielu indeksów w `get_document` -> `invalid_argument`: indeksy
  zapasowe `.ds-*` są ukryte i nie mają prefiksu; obsługa (zasób = nazwa strumienia) do decyzji w M2. Ten kod
  (zamiast `not_found_or_denied`) mówi, co jest pod nazwą, ale tylko komuś, kto ma prawo do `_resolve`; przy
  QueryScope per użytkownik (M3) do ponownej oceny razem z punktem 6 sekcji wspólnej recenzji.
- Poprawna nazwa poza prefiksami serwera (argument `index` i `index_pattern`) -> `not_found_or_denied`, nie
  `invalid_argument` (O-L1): odpowiedź nie mówi, które prefiksy serwer obsługuje.
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
  daje `not_found_or_denied`. Zalecane `do_not_fail_on_forbidden: true` (README). Odczyt `get_document` przez
  alias dodatkowo wymaga `indices:admin/aliases/get`; bez niego alias jest odrzucany jak niewidoczny.
- Brak testu na prawdziwym OpenSearch (O-L4, część testu integracyjnego): `_resolve/index` z `%2C`/`%2A`,
  zachowanie Security przy `_resolve` (403 czy filtrowanie), kształt `GET _alias/<nazwa>` i to, czy `_resolve`
  naprawdę nie podaje atrybutów celów aliasu. Testcontainers z obrazem ~1 GB w środowisku współdzielonym
  odłożone do M3 albo do mocka OpenSearch od ws-01; postać na drucie sprawdza stub (ścieżki raw).
- `outputSchema` narzędzi nie jest zadeklarowany (pola wyniku zależą od konfiguracji); kształt `UntrustedText`
  zachowuje każdy napis z dokumentu, ale `definitionHash` go nie obejmuje.
- Zdarzenie `tool_failed` dla wyjątków domeny (`NotFoundOrDeniedException` itd.) jest logowane na poziomie
  ERROR przez starter; to szum operacyjny, nie błąd serwera (ws-02).

## Czego potrzebują inni

- ws-01: w `mcp-platform-dev` nie ma mocka OpenSearch. Potrzebny WireMock (albo kontener OpenSearch 2.x z
  Security) z: `GET /_resolve/index/{expr}`, `GET /_alias/{name}`, `POST /{expr}/_search`, `GET /{index}/_doc/{id}`,
  `GET /_plugins/_security/authinfo` (whoami brokera, C4.1), tokenem użytkownika `alice` (Bearer), 401 dla
  złego tokenu, 403 dla indeksu bez uprawnień, aliasem prowadzącym poza prefiks i indeksem ukrytym (wzór:
  `mcp-app/src/test/java/.../StubBackend.java`). Do tego `MCP_OPENSEARCH_INDEX_PREFIXES` i `MCP_CONNECT_URL`
  w usłudze `mcp-opensearch` w compose; dopóki mock jest po `http`, także `MCP_OPENSEARCH_ALLOW_PLAIN_HTTP: "true"`
  (poza `dev` serwer przyjmuje tylko `https`; alternatywa: mock po TLS z CA wewnętrznym i `MCP_OPENSEARCH_SSL_BUNDLE`).
- ws-02 (starter): alpha-6 zrealizowało wszystko, o co prosiła runda 1 (walidacja schematu w starterze,
  `@ArgumentLimits`). Do rozważenia: `@ArgumentLimits(contractPattern = "OpenSearchIndexName")` publikuje wzorzec
  z wiodącą kropką (CC-074 zawęża go w 2.1.0); do tego czasu serwer odrzuca kropkę sam, w narzędziu.
- ws-03 (template): `StrictArgumentsInterceptor` nie jest już potrzebny (usunięty z tego serwera);
  `GatewayOnlyAccessTest` ma stałą `GET_RECORD`, którą każdy serwer przerabia.
- ws-05 (gateway): nic nowego ponad README gateway'a.

## Propozycje zmian kontraktu

- CC-074 (C7): `OpenSearchIndexName`/`OpenSearchIndexPrefix` bez wiodącej kropki: przyjęte (2.1.0), serwer już tak
  działa.
- CC-072: kształt `UntrustedText` zastosowany dla napisów w `data` (kontrakty zostają na 2.0.2 przez BOM startera;
  kształt nie jest sprawdzany schematem, bo wynik nie ma `outputSchema`).
- Kategoria (b) z recenzji, bez zmian w serwerze: kod błędu dla zasobu poza zakresem serwera (punkt 6 sekcji
  wspólnej: gitlab/servicedesk `not_found_or_denied`, opensearch teraz też) do doprecyzowania w C10/C7 przed M3.

## Runda 2: znaleziska recenzji M1 i zmiany template'u

Źródło: `review-servers-gitlab-servicedesk-opensearch-M1.md` (sekcje opensearch i wspólna), template STATUS.md
"Zmiany do przeniesienia do serwerów (od 7f65f8f)".

| Znalezisko | Status | Gdzie / test |
| --- | --- | --- |
| O-H1 wstrzyknięcie linii logu przez nazwę argumentu (HIGH) | zamknięte: walidacja w starterze alpha-6, nazwa nie jest logowana ani zwracana; serwerowy test | `SearchLogsToolTest.anUnknownArgumentNameCannotForgeALogLine` |
| 2 testy czerwone na alpha-6 (tekst SDK) | zamknięte: asercje na C10 `invalid_argument` bez echa wartości | `ServerTestSupport.assertInvalidArgument`, `GetDocumentToolTest.invalidIndicesAndIdsAreRejectedBeforeOpenSearch`, `SearchLogsToolTest.argumentsBreakingTheSchemaAreRejectedByTheStarterBeforeOpenSearch` |
| `StrictArgumentsInterceptor` zbędny | usunięty razem z ręcznymi schematami (`OpenSearchToolSchemas`); `additionalProperties: false` dopisuje `SchemaLimitedToolCallbacks`, odrzuca starter | test nadmiarowego argumentu j.w. |
| O-L1 indeks poza zakresem -> `invalid_argument` | `not_found_or_denied` (nazwa poprawna składniowo, w obu narzędziach); składnia nadal `invalid_argument` | `aWellFormedPatternOutsideTheScopeLooksLikeAMissingIndex`, `anIndexOutsideTheScopeLooksLikeAMissingOne` |
| O-L2a indeks ukryty za aliasem przechodził | drugi `_resolve/index` na celach aliasu, cel ukryty/brakujący -> odmowa | `OpenSearchServiceTest.anAliasTargetThatIsHidden...`, `SearchLogsToolTest.anAliasLeadingOutsideThePrefixes...`, `GetDocumentToolTest.anAliasToAHiddenIndexIsNotRead` |
| O-L2b odczyt przez alias bez filtra aliasu | `get_document` odmawia aliasów z filtrem lub routingiem (`GET _alias/<nazwa>`); `search_logs` idzie przez alias, więc filtr działa | `GetDocumentToolTest.anAliasWithAFilterOrRoutingIsNotRead`, `OpenSearchServiceTest.anAliasWithAFilterOrRoutingIsNotReadByDocumentId` |
| O-L3 `http://`, brak limitu rozmiaru, `base-url` z userinfo/query | `https` poza `dev` (wyjątek `MCP_OPENSEARCH_ALLOW_PLAIN_HTTP`), `max-response-size` 1 MB, adres bez userinfo/query/fragmentu | `ProductionConfigurationTest`, `anOversizedBodyIsCutOff` |
| O-L4 testy nie widzą postaci na drucie | stub zapisuje `getRawPath()`/`getRawQuery()`; asercje na `%2C`, `%2A`, `a%3Ab%40c%2Bd%3De` | `aWildcardRightAfterAPrefixStopsAtTheDash`, `theIdIsEncodedOnTheWireAsOnePathSegment` |
| O-L4 test na prawdziwym OpenSearch (Testcontainers) | odłożone (obraz ~1 GB w środowisku współdzielonym) | sekcja "Znane ograniczenia" |
| Wspólne 0 (alpha-5 nadpisane) | serwer na alpha-6 | `pom.xml` |
| Wspólne 1 (walidacja w starterze) | wykorzystane, własne obejścia usunięte | j.w. |
| Wspólne 2 (limity w schemacie) | `@ArgumentLimits` + `SchemaLimitedToolCallbacks.of(...)`, `contractPattern` dla `index` | `GetDocumentToolTest.isListedAsReadOnlyWithAClosedSchema` |
| Wspólne 3 (logi bez escapowania) | dotyczy ws-03; po stronie serwera brak własnych komunikatów z tekstem klienta | - |
| Wspólne 4 (fabryka klienta HTTP) | przeniesione z template'u lokalnie (adapter), docelowo helper startera (ws-02) | `OpenSearchAdapter` |
| Wspólne 5 (kształt oznaczenia) | `UntrustedText` wg CC-072 | `SearchLogsToolTest`, `GetDocumentToolTest` |
| Wspólne 6 (kod dla zasobu poza zakresem) | wyrównane do gitlab/servicedesk; doprecyzowanie C10/C7 to (b) | j.w. |
| Template: https, 3xx, limit rozmiaru, `BackendResponseException`, `ProductionConfigurationTest`, `DevProfileLoopbackTest`, README (dwa sekcje uruchomienia), systemd, `install.sh`, prometheus | przeniesione | `application.yml`, `deploy/`, `README.md` |
