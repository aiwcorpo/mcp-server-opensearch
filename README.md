# mcp-opensearch: serwer MCP platformy

Serwer MCP dla systemu `opensearch`, zbudowany na `mcp-platform-starter` 3.0.0-alpha-5 (Java 25, Spring Boot
4.1.0, Spring AI 2.0.0, kontrakty `mcp-contracts` 2.0.2). Stoi wyłącznie za gateway'em platformy: przyjmuje
ruch tylko po mTLS z wewnętrznym JWT gateway'a (C2), a do systemu docelowego woła z poświadczeniem
użytkownika, które gateway dołącza do każdego żądania (C3). Bez poprawnego JWT odpowiada 401, nie ma trybu
anonimowego.

Logowania użytkowników, decyzji o dostępie do narzędzi i potwierdzeń akcji destrukcyjnych serwer nie robi:
to zadania gateway'a. Serwer odpowiada za narzędzia, adapter do systemu, poprawne błędy (C10) i audyt (C8),
który dokłada starter.

## Szybki start

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
./mvnw verify                 # build, testy przez TestGateway i atrapę backendu; jar: mcp-app/target/mcp-opensearch.jar
```

Testy nie potrzebują Dockera ani środowiska `mcp-platform-dev`: `TestGateway` z `mcp-platform-test` wystawia
JWKS z kluczy testowych kontraktów i podpisuje wewnętrzne JWT, a `StubBackend` (test) udaje API systemu.

## Moduły i miejsce każdej rzeczy

| Moduł | Co w nim jest | W tym serwerze |
| --- | --- | --- |
| `mcp-core` | domena i porty, czysta Java | `IndexScope` (prefiksy, granica na `-`), `IndexPattern`, `LogQuery`, `LogHit`, `IndexDocument`, `ResolvedIndices`, wyjątki domeny; port wejściowy `OpenSearchUseCase`, porty wyjściowe `OpenSearchPort` i `IndexScopeProvider` |
| `mcp-application` | przypadki użycia (bez Springa) | `OpenSearchService`: zakres, rozwiązywanie aliasów, odrzucanie wyników spoza zakresu |
| `mcp-integration` | adaptery wyjściowe do systemu | `OpenSearchAdapter` (REST, poświadczenie z C3, timeouty, budowa zapytania), `ConfiguredIndexScopeProvider`, `UntrustedValues` (escapowanie i limity treści), właściwości `opensearch.*` |
| `mcp-tools` | narzędzia MCP, powierzchnia publiczna | `OpenSearchTools` (`search_logs`, `get_document`), `OpenSearchToolSchemas` (inputSchema), `StrictArgumentsInterceptor`, `OpenSearchToolErrors` (wyjątki domeny -> kody C10) |
| `mcp-app` | bootstrap, wiring, jedyny jar, testy end-to-end | `McpServerApplication`, `McpToolsConfiguration` (lista narzędzi z ręcznymi schematami), `ApplicationWiringConfiguration` |

Zależności idą do środka: `mcp-tools` -> `mcp-application` -> `mcp-core` <- `mcp-integration`; `mcp-app` składa
całość. Resolver zasobów (`@ResourceRef`, starter beta) dojdzie w `mcp-integration` obok adaptera, bo atrybut
`tags` indeksu pochodzi z systemu.

## Narzędzia

| Narzędzie | Ryzyko | Uprawnienie (C9) | Poświadczenie | Argumenty | Wynik |
| --- | --- | --- | --- | --- | --- |
| `search_logs` | `READ_ONLY` | `mcp-opensearch-read` (+ zakres `mcp-opensearch-idx-<prefiks>` od M3) | `user-secret` w M1 (docelowo `token-exchange`), `Authorization: Bearer` albo `Basic` | `index_pattern` (jeden wzorzec `^[a-z0-9-]+\*?$` od dozwolonego prefiksu), `query` (1-512 znaków, tekst `simple_query_string`), `from`, `to` (ISO-8601 z przesunięciem, `to > from`, zakres <= `MCP_OPENSEARCH_MAX_RANGE`), `size` (1-100, domyślnie 20) | `{indexPattern, from, to, returned, hits: [{index, id, timestamp, data}], dataNote}` |
| `get_document` | `READ_ONLY` | jak wyżej | jak wyżej | `index` (jeden konkretny indeks albo alias jednego indeksu, `^[a-z0-9][a-z0-9._-]{0,254}$`, od dozwolonego prefiksu), `id` (`^[A-Za-z0-9_-][A-Za-z0-9_.:@+=-]{0,255}$`) | `{index, id, data, dataNote}` |

Nazwy widziane przez klienta: `opensearch__search_logs`, `opensearch__get_document` (prefiks dokłada gateway, C5).
Błędy obu narzędzi: `not_found_or_denied` (brak indeksu lub dokumentu, 403 z OpenSearch, alias prowadzący poza
prefiksy, indeks ukryty), `credential_required` (brak poświadczenia, 401 z OpenSearch, poświadczenie typu
`service-account` albo schematu innego niż `Bearer`/`Basic`), `invalid_argument`, `upstream_unavailable`,
`internal_error`. Szczegóły i zasady: `docs/api/tools-catalog.md`.

### Zakres indeksów

Prefiks `p` z `MCP_OPENSEARCH_INDEX_PREFIXES` obejmuje indeks `p` i indeksy `p-...`, nigdy `p...` bez myślnika
(`logs-team-a` nie obejmuje `logs-team-ab`; ta sama reguła co `OpenSearchIndexPrefix` w kontraktach i zakresy
Cedar). Kolejne kroki `search_logs`:

1. Składnia: dokładnie jeden wzorzec; odrzucane są `,`, `:`, `<` (date math), wykluczenia `-...`, `_all`, sam
   `*`, wieloznaczniki w środku i indeksy ukryte `.xxx`. Kontraktowy `OpenSearchIndexName` dopuszcza wiodącą
   kropkę; serwer nie (fail-closed).
2. Prefiks: wzorzec musi zaczynać się od dozwolonego prefiksu na granicy `-`. `p*` serwer zamienia na `p,p-*`
   (przecinek pisze serwer, nie model).
3. `GET _resolve/index/<wyrażenie>?expand_wildcards=open`: każdy indeks, każdy cel każdego aliasu musi być w
   zakresie i nie może być ukryty; strumień danych (data stream) -> `invalid_argument` (indeksy `.ds-*` są
   ukryte, obsługa w M2). Coś poza zakresem -> `not_found_or_denied` dla całego wywołania, bez wyszukiwania.
4. `POST <wyrażenie>/_search` z ciałem zbudowanym przez serwer: `simple_query_string` po polach z
   konfiguracji (bez FUZZY/NEAR/SLOP), zakres czasu, filtr `_index` do prefiksów (alias dodany po kroku 3
   nadal nic nie wnosi), `_source.includes` z konfiguracji, `track_total_hits: false`, bez agregacji.
5. Trafienia z indeksów spoza zakresu są odrzucane; z dokumentu zostają tylko pola z `MCP_OPENSEARCH_RESULT_FIELDS`.

`get_document` przechodzi kroki 1-3 dla konkretnej nazwy i czyta dokument z konkretnego indeksu (alias jest
zamieniany na swój jedyny indeks, żeby nie dało się go przepiąć między sprawdzeniem a odczytem).

Serwer nie używa `_msearch`, `_mget`, skryptów, `_search?q=` ani agregacji (agregaty mogłyby liczyć dokumenty
indeksów zakazanych, H2 z recenzji kontraktów 2.0.0, decyzja odłożona).

### Treść z OpenSearch to dane

Wartości dokumentów trafiają do modelu w polu `data` z adnotacją `dataNote`. Znaki `<`, `>`, `&` są
escapowane (`&lt;` ...), więc do klienta nie dochodzi surowy HTML; znaki sterujące poza tabulacją i nową linią
są usuwane; napis do 2000 znaków, do 50 elementów listy lub pól obiektu, głębokość do 6. Wynik nie ma liczby
wszystkich trafień.

### DLS/FLS w OpenSearch: druga warstwa

Serwer woła OpenSearch poświadczeniem użytkownika, więc OpenSearch Security stosuje jego role: uprawnienia do
indeksów, DLS (filtr dokumentów) i FLS (maskowanie pól). Kontrole serwera (prefiksy, aliasy, pola wyniku) to
pierwsza warstwa, która działa także wtedy, gdy role w OpenSearch są za szerokie; DLS/FLS to druga, która
działa, gdy serwer czegoś nie przewidzi. Wymagania wobec ról użytkowników: `indices:admin/resolve/index` i
`indices:data/read/search` / `indices:data/read/get` na ich wzorcach; zalecane
`do_not_fail_on_forbidden: true`, żeby wzorzec obejmujący indeks bez uprawnień zwracał resztę zamiast 403.
Poświadczenie `service-account` jest odrzucane: zastąpiłoby DLS/FLS użytkownika uprawnieniami konta
technicznego.

## Jak przechodzi wywołanie

```
gateway --mTLS (CN mcp-gateway) + JWT C2 (aud mcp-opensearch, tool search_logs) + X-MCP-Credential-->
  MCP SDK: walidacja argumentów względem inputSchema
  starter: InternalJwtFilter (inaczej 401) -> CredentialHeaderFilter -> AuditingToolCallback (C8)
  -> StrictArgumentsInterceptor -> OpenSearchTools.searchLogs: walidacja argumentów
  -> OpenSearchService: IndexScopeProvider.current() -> wyrażenie -> resolve -> kontrola celów
  -> OpenSearchAdapter: CredentialContext.require("opensearch") + credential::applyTo
  -> GET {MCP_OPENSEARCH_URL}/_resolve/index/{expr}, POST {MCP_OPENSEARCH_URL}/{expr}/_search
```

Adapter nie podąża za przekierowaniami (nagłówek z poświadczeniem nie może pójść pod inny adres), czyta tylko
pola domeny i nie wkłada treści odpowiedzi systemu do żadnego komunikatu.

## Konfiguracja

Konfiguracja bazowa (`mcp-app/src/main/resources/application.yml`) jest produkcyjna: mTLS, JWKS gateway'a po
TLS z CA wewnętrznym, `jti` w Redisie. Profil `dev` wyłącza tylko TLS i służy testom; pozostałe ułatwienia
(klucze testowe, `jti` w pamięci, `mtls.required=false`) daje `TestGateway`, a starter odrzuca je poza `dev`.

| Zmienna | Znaczenie | Domyślnie |
| --- | --- | --- |
| `MCP_TLS_KEYSTORE`, `MCP_TLS_TRUSTSTORE`, `MCP_TLS_STORE_PASSWORD` | PKCS12: certyfikat serwera (SAN `mcp-opensearch.internal`) i CA wewnętrzne; truststore służy też do JWKS | wymagane |
| `MCP_GATEWAY_JWKS_URI` | JWKS wewnętrznego JWT, np. `https://mcp-gateway.internal:8443/.well-known/jwks.json` | wymagane |
| `MCP_GATEWAY_ISSUER` | `iss` C2 | `https://mcp-gateway.internal` |
| `MCP_CONNECT_URL` | strona portalu do połączenia konta, z `{system}`; trafia do `credential_required` | wymagane |
| `MCP_OPENSEARCH_URL` | adres REST API OpenSearch | wymagane |
| `MCP_OPENSEARCH_SSL_BUNDLE` | bundle SSL dla HTTPS systemu, np. `mcp-internal-ca`; puste = zaufanie JDK | puste |
| `MCP_OPENSEARCH_INDEX_PREFIXES` | dozwolone prefiksy indeksów, po przecinku (wzorzec `OpenSearchIndexPrefix` bez wiodącej kropki) | wymagane |
| `MCP_OPENSEARCH_TIMESTAMP_FIELD` | pole czasu (zakres i sortowanie) | `@timestamp` |
| `MCP_OPENSEARCH_QUERY_FIELDS` | pola przeszukiwane przez `simple_query_string` | `message` |
| `MCP_OPENSEARCH_RESULT_FIELDS` | jedyne pola `_source` w wyniku (bez wieloznaczników) | `@timestamp,message,log.level,service.name,host.name` |
| `MCP_OPENSEARCH_MAX_RANGE` | najdłuższy zakres `to - from` | `7d` |
| `MCP_REDIS_HOST`, `MCP_REDIS_PORT`, `MCP_REDIS_USER`, `MCP_REDIS_PASSWORD` | Redis dla jednorazowego `jti` (użytkownik ACL `mcp-opensearch`, klucze `jti:mcp-opensearch:*`) | `localhost`, `6379`, `mcp-opensearch`, puste |
| `MCP_PORT`, `MCP_BIND` | port i adres nasłuchu | `8443`, `0.0.0.0` |

Endpoint MCP to `/mcp` (domyślne ustawienie startera, tak jak oczekuje gateway: `internal-url:
https://mcp-opensearch.internal:8443/mcp`). Bez JWT odpowiada tylko `/actuator/health` (i `liveness`,
`readiness`), ale TLS i tak wymaga certyfikatu klienta z CA wewnętrznego.

Serwer nie wstaje bez `MCP_GATEWAY_JWKS_URI`, `MCP_CONNECT_URL`, `MCP_OPENSEARCH_URL`,
`MCP_OPENSEARCH_INDEX_PREFIXES` i plików TLS.

Na hoście ze środowiskiem `mcp-platform-dev` (sprawdzone 2026-10-27 na porcie 18540). Środowisko ma certyfikat
`certs/mcp-opensearch` i użytkownika Redisa `mcp-opensearch`, ale nie ma mocka OpenSearch, więc poniżej
`MCP_OPENSEARCH_URL` wskazuje adres bez usługi: health i 401 działają, wywołania narzędzi dadzą
`upstream_unavailable`.

```bash
dev=../mcp-platform-dev
export MCP_TLS_STORE_PASSWORD="$(grep '^TLS_STORE_PASSWORD=' $dev/.secrets/dev.env | cut -d= -f2-)"
export MCP_REDIS_PASSWORD="$(grep '^REDIS_MCP_OPENSEARCH_PASSWORD=' $dev/.secrets/dev.env | cut -d= -f2-)"
export MCP_TLS_KEYSTORE=$dev/certs/mcp-opensearch/keystore.p12 MCP_TLS_TRUSTSTORE=$dev/certs/ca/truststore.p12
export MCP_PORT=18540 MCP_BIND=127.0.0.1 MCP_REDIS_PORT=16379 MCP_OPENSEARCH_URL=http://localhost:18549
export MCP_OPENSEARCH_INDEX_PREFIXES=logs-team-a,app
export MCP_GATEWAY_JWKS_URI=https://localhost:18445/.well-known/jwks.json
export MCP_CONNECT_URL='http://localhost:18090/connections?system={system}'
java -jar mcp-app/target/mcp-opensearch.jar
curl --cacert $dev/certs/ca/ca.crt --cert $dev/certs/mcp-gateway/tls.crt --key $dev/certs/mcp-gateway/tls.key \
     --resolve mcp-opensearch.internal:18540:127.0.0.1 https://mcp-opensearch.internal:18540/actuator/health   # 200
```

`POST /mcp` bez JWT daje 401, połączenie bez certyfikatu klienta jest zrywane w handshake'u.

## Testy

| Klasa | Co sprawdza |
| --- | --- |
| `SearchLogsToolTest` | poświadczenie z C3 w obu wywołaniach OpenSearch; ciało `_search` (`simple_query_string`, zakres, filtr `_index`, bez agregacji); escapowanie HTML, tylko pola z konfiguracji, odrzucenie trafień spoza prefiksów; `p*` -> `p,p-*`; `readOnlyHint` i schemat (`additionalProperties: false`, limity); brak poświadczenia, 401, `service-account`, schemat `ApiKey` -> `credential_required`; `token-exchange` jak `user-secret`; alias poza zakres (nazwą i wzorcem), indeks bez uprawnień, brakujący i ukryty -> ten sam `not_found_or_denied`; data stream; 16 złych wzorców i 5 spoza zakresu; złe `query`/`from`/`to`/`size`, nadmiarowy argument; błąd 500 bez treści w wyniku i logu |
| `GetDocumentToolTest` | odczyt z C3, tylko pola z konfiguracji, escapowanie; alias czytany przez konkretny indeks; alias wielu indeksów i data stream -> `invalid_argument`; brak poświadczenia, 401; brakujący dokument = indeks bez uprawnień = brakujący = ukryty = alias poza zakres; złe `index` i `id`, indeksy spoza prefiksów |
| `GatewayOnlyAccessTest` | bez JWT 401 (`tools/call`, `ping`, `initialize`), JWT dla innego serwera, innego narzędzia, wariant serwisowy i przeterminowany -> 401; bez JWT tylko health |
| `OpenSearchServiceTest` | granica prefiksu na `-`, wyrażenie `p,p-*`, odrzucanie trafień i dokumentów spoza zakresu, alias z jednym celem poza zakresem (bez Springa) |

`StubBackend` odpowiada kształtami REST API OpenSearch 2.x (`_resolve/index`, `_search`, `_doc`), łącznie z
aliasami, indeksem ukrytym, data streamem i 403 OpenSearch Security. Nie jest to prawdziwy klaster (STATUS.md).

Nowe narzędzie dostaje test w tym samym stylu: klasa dziedziczy po `ServerTestSupport`, odpowiedź systemu
dopisuje się w `StubBackend`.

## Dokumentacja

- `docs/jak-napisac-narzedzie.md`: zasady i kolejność kroków przy nowym narzędziu.
- `docs/architecture/overview.md`: architektura i decyzje.
- `docs/security/SECURITY.md`: niezmienniki bezpieczeństwa w tym serwerze.
- `docs/api/tools-catalog.md`: rejestr narzędzi.
- `docs/operations/deployment-vm-systemd.md`, `deploy/`, `Dockerfile`: wdrożenie.
