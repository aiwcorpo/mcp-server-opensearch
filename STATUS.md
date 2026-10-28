# STATUS - mcp-server-opensearch (ws-04)

M3: osiągnięty (2026-10-28) - serwer na starterze 3.0.0-beta-2 (kontrakty 3.1.0): `@ToolTitle`, zamknięte schematy, `policy.*` i `manifest.*`, `GET /mcp-manifest` zgodny z `tools/list`; `@ResourceRef` + `OpenSearchIndexResolver` dla `OpenSearch::Index` (`name`, `tags` z konfiguracji); zakres z `QueryScope` (`opensearchIndexExpression()`, `nothingToQuery()` -> odmowa, `OPENSEARCH_SEARCH_PARAMETERS`); zapytania tylko z ustrukturyzowanych filtrów (`text`, `level`, `service`); `ResultFilter` na trafieniach `search_logs`; `aggregate_logs` odmawia dla aliasów i liczy tylko indeksy dopuszczone polityką; 107 testów, 0 błędów.

## M3: kamienie pośrednie

| Kamień | Zawartość | Status |
| --- | --- | --- |
| M3.1 | alpha-12..14 (bez zmian w kodzie: `DevProfileLoopbackTest` już z alpha-11, brak wykluczeń platformy), beta-1 i beta-2: BOM, tytuły, `policy.*`, `manifest.*`, Dockerfile bez Alpine, `StateDirectory` w systemd | zrobione |
| M3.2 | `QueryScope` jako źródło zakresu, `@ResourceRef` i resolver, `ResultFilter`, odmowa przy pustym zakresie | zrobione |
| M3.3 | filtry zamiast `simple_query_string`, `aggregate_logs`, manifest, testy i dokumentacja | zrobione |

Zakres M3 poza listą wyżej: starter beta-3 nie istnieje w momencie zamknięcia (tagi startera kończą się na `v3.0.0-beta-2`);
przejście na niego to podbicie `mcp-platform.version` i pozycje z "Z beta-2 na beta-3".

## Migracja na starter 3.0.0-beta-2 (2026-10-28)

Źródło: `MIGRATION-3.0.md`, "Jak przejść z alpha-11 na alpha-14", "Z alpha-14 na beta-1", "Z beta-1 na beta-2".

| Punkt | Status |
| --- | --- |
| Wersje: BOM 3.0.0-beta-2, README (starter, kontrakty 3.1.0) | zrobione |
| `DevProfileLoopbackTest`, wykluczenia platformy | już zgodne (alpha-11); `grep autoconfigure.exclude` pusty |
| `@ToolTitle` (polski, do 100 znaków) | "Szukaj w logach", "Zlicz logi według pola", "Pobierz dokument z logów" |
| Schemat wejścia zamknięty i ograniczony | `@ArgumentLimits` na każdym argumencie; wzorce zakotwiczone, bez `|` na wierzchu (poziom i grupowanie jako `^(?:a|b)$`), bez map i `$ref` |
| Higiena tekstu | `text` przez `TextHygiene.firstProblem`; wartości z systemu przez `UntrustedValues` (`label` dla grup) |
| `policy.*`, `manifest.*` w `application.yml`, `application-dev.yml`, szablonie env i testach | zrobione; `allow-test-keys` tylko w `dev` (`ProductionConfigurationTest`) |
| `@ResourceRef` i resolver | `get_document.index`; `search_logs.index_pattern` nie (wzorzec z `*` nie jest nazwą indeksu); wynik `search_logs` przez `LogHitView.index` |
| Wyszukiwanie przez `StructuredQuery` (beta-2) | dla OpenSearch odpowiednikiem jest `QueryScope.opensearchIndexExpression()` plus ciało budowane z wartości; model nie podaje DSL ani `simple_query_string` |
| Wynik śledzony przez filtr | rekordy, bez `Map`; `returned` usunięte (licznik sprzed filtra) |
| Kody błędów resolvera, `invalid_argument` tylko dla wzorca | awaria OpenSearch w resolverze -> `upstream_unavailable`/`internal_error`; pusty `Optional` dla 403/404 i dla wszystkiego, czego serwer nie czyta |
| Cykl beanów `ResourcePolicy` <-> resolver <-> zakres | `QueryScopeIndexScopeProvider` bierze `ObjectProvider<ResourcePolicy>` |
| Obraz | `eclipse-temurin:25-jre` (glibc), nie Alpine |

Weryfikacja: `./mvnw -B clean verify` (JDK 25) -> 107 testów, 0 błędów (mcp-application 15, mcp-integration 12, mcp-tools 3,
mcp-app 77). Starter nie ma jeszcze mocka OpenSearch w `mcp-platform-dev`; paczka polityk z `:18747` jest używana tylko
przy uruchomieniu procesu na hoście.

## Odstępstwa od zadania M3 i decyzje

- `aggregate_logs` jest nowym narzędziem. STATUS z M1 odkładał agregacje do decyzji człowieka (H2), ale zadanie M3 wymaga
  odmowy dla aliasów w agregatach i testu, więc istnieje jedno wąskie narzędzie: `terms` po `level`/`service`/`host`
  (pola z konfiguracji), do 50 grup, indeksy wymienione z nazwy (do 100), bez `hits.total` i `_count`. Do zatwierdzenia przez
  człowieka razem z `definitionHash`.
- Zmiana kształtu wejścia `search_logs`: `query` (składnia `simple_query_string`) zastąpione przez `text` (słowa, `multi_match`
  z `and`) oraz opcjonalne `level` i `service`. Wynik bez `returned`. Zmienia `definitionHash`.
- Brak grantów, pusty przekrój linku z grantami i indeks poza grantami dają `not_found_or_denied` (ten sam tekst co brakujący
  indeks), także dla `search_logs`; doc template'u mówi o pustym wyniku dla linku. Wybór zgodny z zadaniem (odmowa) i bez
  ujawniania istnienia zakresów.
- `get_document`: alias kilku indeksów i data stream to teraz `not_found_or_denied` (resolver nie ma jak zwrócić
  `invalid_argument`, a kod odróżniał to, co jest pod nazwą). `search_logs` dla data stream zostaje `invalid_argument`.
- Tagi indeksów (`OpenSearch::Index.tags`) z konfiguracji `opensearch.scope.index-tags`; indeks bez reguły ma `[]`, czyli
  jest dostępny w granicie. Klasyfikacja `restricted` wymaga wpisu w konfiguracji (OpenSearch nie ma tagów).
- Paczka przykładowa kontraktów zna prefiksy `logs-team-a`, `logs-team-b`; testy używają ich (stare `app` zniknęło).
- Prawdziwego OpenSearch nadal nie uruchamiałem (stub z kształtami API 2.x); kształt odpowiedzi agregacji `terms` niepotwierdzony na klastrze.

## Propozycje CC

- CC-A (C7 / entity-builder.md sekcja 8): doprecyzować, co serwer zwraca, gdy `nothingToQuery()` jest prawdą dla wyszukiwania
  (pusty wynik dla linku wykluczającego wszystko, jak w szablonie, czy `not_found_or_denied`). Serwer OpenSearch zwraca
  `not_found_or_denied` w obu przypadkach.
- CC-B (starter, nie kontrakty): `ResourceResolver` i `QueryScope` wymagają obiegu przez `ObjectProvider<ResourcePolicy>`, gdy
  resolver korzysta z zakresu; warto opisać w README startera (cykl `ResourcePolicy` -> `ResourceResolvers` -> resolver).
- CC-C (C7): źródło `OpenSearch::Index.tags`. Dziś konfiguracja serwera; jeśli platforma ma je trzymać centralnie (paczka
  polityk, a nie plik serwera), trzeba to rozstrzygnąć przed produkcją.
- Z M1 bez zmian: kategoria (b) z recenzji, kod dla zasobu poza zakresem serwera.

M1: osiągnięty (2026-10-27, runda 2; 2026-10-28 przeniesiony na starter 3.0.0-alpha-10) - `search_logs` i `get_document` (READ_ONLY) na starterze 3.0.0-alpha-10, walidacja argumentów przez starter (C10), jeden wzorzec indeksu od prefiksu z konfiguracji, aliasy rozwiązywane i sprawdzane (cele ukryte, filtr), poświadczenie z C3, 69 testów na stubie OpenSearch 2.x.

Starter: BOM `pl.aiwcorpo.mcp:mcp-platform-bom:3.0.0-alpha-11` (kontrakty 2.1.1, Spring Boot 4.1.0, Spring AI
2.0.0, Java 25). Repo wygenerowane `new-server.sh opensearch` z template'u 7f65f8f (pierwszy commit); zmiany template'u do c1258c4
przeniesione w rundzie 2. M2 nie jest zaczęty.

## Migracja na starter 3.0.0-alpha-10 (2026-10-28)

BOM alpha-6 -> alpha-10 (kontrakty 2.1.1). Źródło: `MIGRATION-3.0.md`, alpha-7 do alpha-10. M2 nie jest zaczęty.

| Punkt | Status |
| --- | --- |
| Wynik jako rekord z `UntrustedText` startera, bez `Map`/`Object` | zrobione z przebudową kształtu: `data` było `Map<String, Object>`, a generator `outputSchema` startera przyjmuje tylko rekordy, listy i skalary. Teraz `data` to lista `LogField{name, path, value}` (obiekty i listy spłaszczone do liści, `path` z nazwą z dokumentu jako `UntrustedText`, liczby i wartości logiczne jako tekst JSON). Zmiana kształtu wyniku dla klienta, `definitionHash` zmieniony i tak przez `outputSchema`. Własny `UntrustedText` usunięty; `mcp-core` zależy od startera tylko dla tego typu |
| `@Nullable` na polach, które system może zostawić puste | `LogHit.id`/`timestamp`, `IndexDocument.id`, `DocumentResult.id`, `LogField.path` |
| Kolejność tekstu i spójna obsługa (wspólne 1 z rundy 2) | `UntrustedValues`: tekst, `normalize`, `cut`, `plainCut`; escapowanie `<`, `>`, `&` usunięte (klient nie renderuje HTML, ta sama reguła co gitlab i servicedesk). Test: `<script>alert(1)</script> & retried` wychodzi bez zmiany |
| O2-L2 (klucze obiektów i `id` poza `UntrustedText`) | zamknięte: nazwy z dokumentu w `path` jako `UntrustedText`; `id` tylko w kształcie `get_document` (inaczej `null`), `timestamp` tylko data lub liczba |
| Limit wyniku 1 MB (A9) | `search_logs` ma budżet 900000 bajtów szacowanego JSON (`SearchLogsResult.of`, od rundy 4; wcześniej 250000 znaków tekstu), po przekroczeniu `truncated: true` i reszta trafień pominięta; do 100 wpisów na dokument; testy `SearchLogsResultTest`, `UntrustedValuesTest` |
| Limity na każdym poziomie schematu | bez zmian: `@ArgumentLimits` na wszystkich argumentach (`size` z `minimum`/`maximum`), wzorce zakotwiczone, brak `|` |
| CC-074: `get_document` bez wiodącej kropki we wzorcu | `contractPattern = "OpenSearchIndexName"` publikuje od 2.1.1 `^[a-z0-9][a-z0-9._-]{0,254}$` (test `isListedAsReadOnlyWithAClosedSchema`); własny filtr kropki w narzędziu i kopia wzorca usunięte, wzorzec czytany z kontraktu. `.security` dalej `invalid_argument` (istniejące testy) |
| O2-L1 test routingu aliasu | `anAliasWithOnlyARoutingIsNotRead`: aliasy `app-routed-index` i `app-routed-search` (sam routing, bez filtra) w stubie, `get_document` -> `not_found_or_denied` bez odczytu `_doc` |
| `tool_failed` domenowe na INFO | `ToolFailureLogLevelTest`; testów oczekujących ERROR nie było |
| `dev` i obejścia w `application-dev.yml` | usunięte `replay-store: memory` i `mtls.required: false`; `SPRING_PROFILES_ACTIVE=dev` tylko w poleceniu README; `overridden=[]` w logu |
| Własne beany polityki i audytu | brak |
| Claimy `vs` i `vsc:{}` | `GatewayLinkClaimsTest` |
| Zgodność z mockiem ws-01 | mocka OpenSearch nie ma (STATUS wyżej); stub bez zmian w kształtach odpowiedzi |

Weryfikacja: `./mvnw -B clean verify` (JDK 25) -> 71 testów, 0 błędów (mcp-app 55, mcp-application 8, mcp-integration 5,
mcp-tools 3). Uruchomienie wg README: `dev` na 18541, health 200, `POST /mcp` bez JWT 401; proces zatrzymany.

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
  napis w `data` jako `UntrustedText` (CC-072, bez escapowania od alpha-10), limity długości, liczby wpisów i rozmiaru.
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
- Agregacje: od M3 `aggregate_logs` (patrz niżej); H2 z recenzji kontraktów 2.0.0 zamknięte przez wyłączenie indeksów zakazanych z zapytania i odmowę dla aliasów.
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

## Runda 4: poprawki przeglądu alpha-10 (2026-10-28)

| Znalezisko | Status | Dowód |
| --- | --- | --- |
| O3-M1 budżet nie liczył narzutu JSON wpisu (`internal_error` przy 100 x 100 krótkich wpisach) | poprawione: budżet w bajtach szacowanego JSON (`LogField.jsonSize`: 250 B narzutu na wpis plus tekst w UTF-8, cudzysłowy, ukośniki i końce linii liczone podwójnie; `LogHit.jsonSize`: 400 B narzutu na trafienie), limit 900000 B przy limicie startera 1048576 B | `SearchLogsResultTest.manyShortEntriesAreCountedWithTheirJsonOverhead` (100 trafień po 100 wpisów: `truncated`, zserializowany wynik poniżej 1 MiB) |
| O3-L1 cięcie `path` bez testu | test | `UntrustedValuesTest.aLongKeyOfADocumentIsCutInThePathAndSaysSo` |
| A10-M1 `DevProfileLoopbackTest` | bez zmian, rozwiąże starter alpha-11 | |

## Runda 5: starter alpha-11 (2026-10-28)

BOM alpha-10 -> alpha-11 (MIGRATION-3.0.md, sekcja "Zmiany w 3.0.0-alpha-11"). `DevProfileLoopbackTest` sprawdza regułę guarda przez `DevProfileGuardAssertions.assertDevProfileLoopbackRule()` (A10-M1), więc przechodzi także w kontenerze CI. `./mvnw clean verify`: 71 testów, 0 błędów; `INVOCATION_ID=ci ./mvnw -B verify`: bez różnic. Start lokalny w profilu `dev`: health 200, POST /mcp bez JWT 401.
