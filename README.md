# mcp-opensearch: serwer MCP platformy

Serwer MCP dla systemu `opensearch`, zbudowany na `mcp-platform-starter` 3.0.0-beta-4 (Java 25, Spring Boot
4.1.0, Spring AI 2.0.0, kontrakty `mcp-contracts` 3.1.0). Stoi wyłącznie za gateway'em platformy: przyjmuje
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
| `mcp-integration` | adaptery wyjściowe do systemu | `OpenSearchAdapter` (REST, poświadczenie z C3, timeouty, bez przekierowań, `https` poza `dev`, budowa zapytania), `BoundedBodyInterceptor` (limit rozmiaru odpowiedzi), `BackendResponseException` (odpowiedź bez treści w komunikacie), `ConfiguredIndexScopeProvider`, `UntrustedValues` (spłaszczenie wartości do wpisów, normalizacja, limity), właściwości `opensearch.*` |
| `mcp-tools` | narzędzia MCP, powierzchnia publiczna | `OpenSearchTools` (`search_logs`, `get_document`, `@ArgumentLimits` na argumentach), `OpenSearchToolErrors` (wyjątki domeny -> kody C10) |
| `mcp-app` | bootstrap, wiring, jedyny jar, testy end-to-end | `McpServerApplication`, `McpToolsConfiguration` (lista narzędzi przez `SchemaLimitedToolCallbacks.of(...)` ze startera), `ApplicationWiringConfiguration` |

Zależności idą do środka: `mcp-tools` -> `mcp-application` -> `mcp-core` <- `mcp-integration`; `mcp-app` składa
całość. Resolver zasobów (`@ResourceRef`, starter beta) dojdzie w `mcp-integration` obok adaptera, bo atrybut
`tags` indeksu pochodzi z systemu.

## Narzędzia

| Narzędzie (tytuł) | Ryzyko | Akcja | Uprawnienie (C9) | Poświadczenie | Argumenty | Wynik |
| --- | --- | --- | --- | --- | --- | --- |
| `search_logs` ("Szukaj w logach") | `READ_ONLY` | `read` | grupa `mcp-opensearch-read` i co najmniej jeden zakres `mcp-opensearch-idx-<prefiks>` | `user-secret` albo `token-exchange`, `Authorization: Bearer` lub `Basic` | `index_pattern` (jeden wzorzec `^[a-z0-9-]+\*?$` od prefiksu z grantów), `text` (1-512 znaków, słowa; operatory to zwykły tekst), opcjonalnie `level` (`TRACE`...`FATAL`) i `service` (nazwa), `from`, `to` (ISO-8601 z przesunięciem, `to > from`, zakres <= `MCP_OPENSEARCH_MAX_RANGE`), `size` (1-100, domyślnie 20) | `{indexPattern, from, to, hits: [{index, id, timestamp, data, truncated}], truncated}`; `index` to `@ResourceRef`; `data` to wpisy `{name, path, value}`, `path` i `value` jako `UntrustedText`; bez liczników |
| `aggregate_logs` ("Zlicz logi według pola") | `READ_ONLY` | `read` | jak wyżej | jak wyżej | `index_pattern`, `group_by` (`level`, `service`, `host`), opcjonalnie `text`, `level`, `service`, `from`, `to`, `size` (1-50, domyślnie 10) | `{indexPattern, from, to, groupBy, buckets: [{key, count}]}`; `key` jako `UntrustedText` |
| `get_document` ("Pobierz dokument z logów") | `READ_ONLY` | `read` | jak wyżej | jak wyżej | `index` (`@ResourceRef` typu `OpenSearch::Index`; jeden konkretny indeks albo alias jednego indeksu, `^[a-z0-9][a-z0-9._-]{0,254}$`), `id` (`^[A-Za-z0-9_-][A-Za-z0-9_.:@+=-]{0,255}$`) | `{index, id, data, truncated}`; `data` jak w `search_logs` |

Nazwy widziane przez klienta: `opensearch__search_logs`, `opensearch__aggregate_logs`, `opensearch__get_document`
(prefiks dokłada gateway, C5). Model podaje wartości (tekst, poziom, usługa, pole grupowania), nigdy zapytania:
`multi_match` z operatorem `and` po polach z konfiguracji, `term` dla poziomu i usługi oraz jedna agregacja `terms`
na polu z konfiguracji buduje serwer. Wynik `search_logs` i `get_document` nie przekracza 1 MB (budżet 900000 B
szacowanego JSON, po przekroczeniu `truncated: true`).
Błędy narzędzi: `not_found_or_denied` (brak indeksu lub dokumentu, 403 z OpenSearch, brak grantów, indeks poza
grantami albo linkiem, indeks z tagiem `restricted`, alias prowadzący poza zakres albo do indeksu ukrytego, alias
kilku indeksów, z filtrem lub routingiem w `get_document`, data stream w `get_document`, indeks ukryty),
`access_denied` (brak grupy `mcp-opensearch-read`), `credential_required` (brak poświadczenia, 401 z OpenSearch,
poświadczenie typu `service-account` albo schematu innego niż `Bearer`/`Basic`), `invalid_argument` (także
`aggregate_logs` na aliasie albo na zbyt wielu indeksach), `upstream_unavailable`, `internal_error`. Szczegóły:
`docs/api/tools-catalog.md`.

### Polityki, zakres i klasyfikacja (M3)

- `get_document.index` ma `@ResourceRef(type = "OpenSearch::Index")`. Resolver (`OpenSearchIndexResolver`) pyta
  OpenSearch poświadczeniem użytkownika, w zakresie grantów, i opisuje indeks, który OpenSearch faktycznie podaje
  (alias jednego indeksu -> ten indeks): `name` i `tags`. Brak, zakaz i odmowa polityki to ten sam `not_found_or_denied`.
- `tags` (atrybut obowiązkowy) pochodzą z konfiguracji, bo OpenSearch nie ma tagów: `opensearch.scope.index-tags`
  (lista `{prefix, tags}`; prefiks obejmuje `p` i `p-...`; w środowisku `OPENSEARCH_SCOPE_INDEXTAGS_0_PREFIX`,
  `OPENSEARCH_SCOPE_INDEXTAGS_0_TAGS_0`). Indeks bez reguły ma `tags: []`. Paczka polityk zabrania `restricted`.
- Zakres wyszukiwania z `QueryScope`: grupy `mcp-opensearch-idx-<prefiks>` (mapowanie w paczce) przecięte z linkiem
  wirtualnego serwera (`opensearchIndexPrefixes`) i z `MCP_OPENSEARCH_INDEX_PREFIXES`. Kolejne `p` dają wyrażenie
  `p,p-*` (nigdy `p*`: `payments-old` nie wchodzi do zakresu `p`). Gdy `QueryScope.nothingToQuery()` (brak grantów albo
  link i granty się nie spotykają), serwer odmawia (`not_found_or_denied`) i nie wysyła żadnego zapytania: puste
  wyrażenie indeksów oznaczałoby wszystkie indeksy. Każde żądanie z wyrażeniem niesie `expand_wildcards=open`,
  `ignore_unavailable=true`, `allow_no_indices=true` (`QueryScope.OPENSEARCH_SEARCH_PARAMETERS`).
- `search_logs`: `LogHitView.index` to `@ResourceRef`, więc starter po wykonaniu usuwa trafienia z indeksów, których
  polityka nie dopuszcza (np. tag `restricted`). Wynik nie ma liczników (`returned`, `total`).
- `aggregate_logs`: liczby nie dają się poprawić po fakcie, więc do zapytania trafiają tylko konkretne indeksy, które
  `_resolve/index` zwrócił i które polityka dopuszcza (`ResourcePolicy.allows`, do 100 indeksów). Gdy `_resolve/index`
  zwraca alias (README startera: wzorzec `p-*` obejmuje też aliasy), narzędzie odmawia z `invalid_argument`; alias
  prowadzący poza zakres jest najpierw zwykłym `not_found_or_denied`.

### Zakres indeksów

Prefiks `p` z `MCP_OPENSEARCH_INDEX_PREFIXES` obejmuje indeks `p` i indeksy `p-...`, nigdy `p...` bez myślnika
(`logs-team-a` nie obejmuje `logs-team-ab`; ta sama reguła co `OpenSearchIndexPrefix` w kontraktach i zakresy
Cedar). Kolejne kroki `search_logs`:

0. Zakres wywołania wynika z grantów (wyżej); pusty zakres kończy wywołanie. Argumenty sprawdza najpierw starter względem `inputSchema` (`invalid_argument` bez nazwy i wartości), potem
   narzędzie.
1. Składnia: dokładnie jeden wzorzec; odrzucane są `,`, `:`, `<` (date math), wykluczenia `-...`, `_all`, sam
   `*`, wieloznaczniki w środku i indeksy ukryte `.xxx`. Od kontraktów 2.1 (CC-074) wzorzec
   `OpenSearchIndexName`, publikowany w `inputSchema` `get_document`, sam wyklucza wiodącą kropkę; serwer nie ma już
   własnego filtra kropki w `get_document` (zostaje w `index_pattern` i w `IndexScope`).
2. Prefiks: wzorzec musi zaczynać się od dozwolonego prefiksu na granicy `-`; inaczej `not_found_or_denied`
   (poprawna nazwa spoza zakresu wygląda jak brakujący indeks). `p*` serwer zamienia na `p,p-*` (przecinek
   pisze serwer, nie model).
3. `GET _resolve/index/<wyrażenie>?expand_wildcards=open`: każdy indeks, każdy cel każdego aliasu musi być w
   zakresie i nie może być ukryty; strumień danych (data stream) -> `invalid_argument` w wyszukiwaniu i
   `not_found_or_denied` w `get_document`. Coś poza zakresem -> `not_found_or_denied` dla całego wywołania, bez wyszukiwania.
   `_resolve/index` nie podaje atrybutów celów aliasu, więc cele aliasów są rozwiązywane drugi raz po nazwie
   (tam atrybut `hidden` jest podany); cel ukryty, brakujący albo niewidoczny odrzuca całe wywołanie.
4. `POST <wyrażenie>/_search` z ciałem zbudowanym przez serwer: `multi_match` (operator `and`) po polach z
   konfiguracji, opcjonalne `term` poziomu i usługi, zakres czasu, filtr `_index` do prefiksów (alias dodany po kroku 3
   nadal nic nie wnosi), `_source.includes` z konfiguracji, `track_total_hits: false`.
5. Trafienia z indeksów spoza zakresu są odrzucane; z dokumentu zostają tylko pola z `MCP_OPENSEARCH_RESULT_FIELDS`.

`get_document` przechodzi kroki 1-3 dla konkretnej nazwy i czyta dokument z konkretnego indeksu (alias jest
zamieniany na swój jedyny indeks, żeby nie dało się go przepiąć między sprawdzeniem a odczytem). Odczyt po id
omija filtr i routing aliasu, więc alias, który któreś ma (`GET _alias/<nazwa>`), jest odrzucany jako
`not_found_or_denied`. W `search_logs` alias z filtrem działa normalnie: wyszukiwanie idzie przez niego.

Serwer nie używa `_msearch`, `_mget`, skryptów, `_count` ani `_search?q=`. Jedyna agregacja to `terms` w
`aggregate_logs` (krok 3 i zasady wyżej).

### Treść z OpenSearch to dane

Wartości pól z `MCP_OPENSEARCH_RESULT_FIELDS` trafiają do modelu w polu `data` jako lista wpisów
`{name, path, value}` (wynik jest rekordem, więc ma `outputSchema`; mapa nie ma ograniczonego schematu).
`name` to pole z konfiguracji. Pole z obiektem albo listą daje po jednym wpisie na liść, `path` (`a.b`, `[2]`) to
nazwa z dokumentu, czyli też tekst od kogokolwiek, kto może coś zalogować. `path` i `value` mają kształt
`UntrustedText` (CC-072): `{"kind":"untrusted-data","format":"text/plain","text":"...","truncated":false}`.
Liczby i wartości logiczne są tekstem w zapisie JSON, `null` jest pomijany.

Kolejność: wartość jako tekst, `TextHygiene.normalize` (znak sterujący lub niewidoczny zastępuje spacja), cięcie
(`UntrustedText.cut`: wartość 2000 znaków, `path` 128), `plainCut`. Tekst nie jest escapowany: `<`, `>` i `&`
zostają, klient nie renderuje go jako HTML (ta sama reguła w gitlab i servicedesk). Do 100 wpisów na dokument i
głębokość 6, dalej `truncated: true` w trafieniu. Wynik `search_logs` ma budżet 250000 znaków tekstu (platforma
odrzuca wynik ponad 1 MB): po jego przekroczeniu reszta trafień jest pomijana i `truncated: true` w wyniku.
`index` to nazwa z zakresu, `id` to napis tylko wtedy, gdy ma kształt przyjmowany przez `get_document`
(w przeciwnym razie `null`), `timestamp` tylko gdy wygląda jak data lub liczba. Pola puste są `@Nullable` w
schemacie wyniku. Wynik nie ma liczby wszystkich trafień.

### DLS/FLS w OpenSearch: druga warstwa

Serwer woła OpenSearch poświadczeniem użytkownika, więc OpenSearch Security stosuje jego role: uprawnienia do
indeksów, DLS (filtr dokumentów) i FLS (maskowanie pól). Kontrole serwera (prefiksy, aliasy, pola wyniku) to
pierwsza warstwa, która działa także wtedy, gdy role w OpenSearch są za szerokie; DLS/FLS to druga, która
działa, gdy serwer czegoś nie przewidzi. Wymagania wobec ról użytkowników: `indices:admin/resolve/index`,
`indices:admin/aliases/get` (tylko dla `get_document` przez alias) i `indices:data/read/search` /
`indices:data/read/get` na ich wzorcach; zalecane
`do_not_fail_on_forbidden: true`, żeby wzorzec obejmujący indeks bez uprawnień zwracał resztę zamiast 403.
Poświadczenie `service-account` jest odrzucane: zastąpiłoby DLS/FLS użytkownika uprawnieniami konta
technicznego.

## Jak przechodzi wywołanie

```
gateway --mTLS (CN mcp-gateway) + JWT C2 (aud mcp-opensearch, tool search_logs) + X-MCP-Credential-->
  starter: InternalJwtFilter (inaczej 401) -> CredentialHeaderFilter -> AuditingToolCallback (C8):
           walidacja argumentów względem inputSchema (C10 invalid_argument)
  -> OpenSearchTools.searchLogs: druga linia walidacji
  -> OpenSearchService: IndexScopeProvider.current() -> wyrażenie -> resolve -> kontrola celów
  -> OpenSearchAdapter: CredentialContext.require("opensearch") + credential::applyTo
  -> GET {MCP_OPENSEARCH_URL}/_resolve/index/{expr}, POST {MCP_OPENSEARCH_URL}/{expr}/_search
```

Adapter nie podąża za przekierowaniami (nagłówek z poświadczeniem nie może pójść pod inny adres; 3xx to błąd,
nie "brak zasobu"), czyta najwyżej `max-response-size` bajtów, tylko pola domeny, i nie wkłada treści
odpowiedzi systemu do żadnego komunikatu ani logu.

## Konfiguracja

Konfiguracja bazowa (`mcp-app/src/main/resources/application.yml`) jest produkcyjna: mTLS, JWKS gateway'a po
TLS z CA wewnętrznym, `jti` w Redisie, adres OpenSearch tylko po `https`. Profil `dev` (testy i uruchomienie
lokalne, niżej) wyłącza TLS i daje ułatwienia, które starter odrzuca poza `dev`.

| Zmienna | Znaczenie | Domyślnie |
| --- | --- | --- |
| `MCP_TLS_KEYSTORE`, `MCP_TLS_TRUSTSTORE`, `MCP_TLS_STORE_PASSWORD` | PKCS12: certyfikat serwera (SAN `mcp-opensearch.internal`) i CA wewnętrzne; truststore służy też do JWKS | wymagane |
| `MCP_GATEWAY_JWKS_URI` | JWKS wewnętrznego JWT, np. `https://mcp-gateway.internal:8443/.well-known/jwks.json` | wymagane |
| `MCP_GATEWAY_ISSUER` | `iss` C2 | `https://mcp-gateway.internal` |
| `MCP_CONNECT_URL` | strona portalu do połączenia konta, z `{system}`; trafia do `credential_required` | wymagane |
| `MCP_OPENSEARCH_URL` | adres REST API OpenSearch | wymagane |
| `MCP_OPENSEARCH_SSL_BUNDLE` | bundle SSL dla HTTPS systemu, np. `mcp-internal-ca`; puste = zaufanie JDK | puste |
| `MCP_OPENSEARCH_ALLOW_PLAIN_HTTP` | `true` pozwala na `http://` w `MCP_OPENSEARCH_URL` poza profilem `dev`, wyłącznie dla mocków `mcp-platform-dev`; poświadczenie idzie wtedy otwartym tekstem | `false` |
| `MCP_OPENSEARCH_INDEX_PREFIXES` | górna granica prefiksów indeksów, po przecinku (wzorzec `OpenSearchIndexPrefix` bez wiodącej kropki); grant spoza listy nic nie daje | wymagane |
| `MCP_OPENSEARCH_TIMESTAMP_FIELD` | pole czasu (zakres i sortowanie) | `@timestamp` |
| `MCP_OPENSEARCH_QUERY_FIELDS` | pola, w których szukany jest `text` | `message` |
| `MCP_OPENSEARCH_LEVEL_FIELD`, `MCP_OPENSEARCH_SERVICE_FIELD`, `MCP_OPENSEARCH_HOST_FIELD` | pola keyword filtrów `level`, `service` i grupowania | `log.level`, `service.name`, `host.name` |
| `MCP_POLICY_URL`, `MCP_POLICY_KEY_ID`, `MCP_POLICY_PUBLIC_KEY_FILE`, `MCP_POLICY_STATE_DIR` | podpisana paczka polityk (Ed25519), katalog stanu z ostatnią rewizją | wymagane poza `dev` |
| `MCP_INTERNAL_URL`, `MCP_ACCEPTED_CREDENTIAL_TYPES` | opis serwera w manifeście (`GET /mcp-manifest`) | wymagane / `user-secret` |
| `MCP_OPENSEARCH_RESULT_FIELDS` | jedyne pola `_source` w wyniku (bez wieloznaczników) | `@timestamp,message,log.level,service.name,host.name` |
| `MCP_OPENSEARCH_MAX_RANGE` | najdłuższy zakres `to - from` | `7d` |
| `MCP_REDIS_HOST`, `MCP_REDIS_PORT`, `MCP_REDIS_USER`, `MCP_REDIS_PASSWORD` | Redis dla jednorazowego `jti` (użytkownik ACL `mcp-opensearch`, klucze `jti:mcp-opensearch:*`) | `localhost`, `6379`, `mcp-opensearch`, puste |
| `MCP_PORT`, `MCP_BIND` | port i adres nasłuchu | `8443`, `0.0.0.0` |

Endpoint MCP to `/mcp` (domyślne ustawienie startera, tak jak oczekuje gateway: `internal-url:
https://mcp-opensearch.internal:8443/mcp`). Bez JWT odpowiada tylko `/actuator/health` (i `liveness`,
`readiness`), ale TLS i tak wymaga certyfikatu klienta z CA wewnętrznego.

Serwer nie wstaje bez `MCP_GATEWAY_JWKS_URI`, `MCP_CONNECT_URL`, `MCP_OPENSEARCH_URL`,
`MCP_OPENSEARCH_INDEX_PREFIXES`, paczki polityk (`MCP_POLICY_*`) i plików TLS. W `dev` paczka pochodzi z
`http://127.0.0.1:18747/policies/main/latest.json` (klucz z `mcp-policies/keys/dev/`, ścieżka względem katalogu startu).

## Uruchomienie lokalne bez środowiska

Profil `dev` (musi być jedynym aktywnym profilem): zwykłe HTTP na 127.0.0.1, `jti` w pamięci, bez Redisa,
certyfikatów i sekretów. Gateway'a nie ma, więc każde żądanie z JWT kończy się 503 (JWKS niedostępny); ten tryb
służy do sprawdzenia, że serwer wstaje i odrzuca ruch bez JWT. Narzędzia sprawdzają testy (`./mvnw verify`).

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
./mvnw -B verify
SPRING_PROFILES_ACTIVE=dev MCP_PORT=18541 "$JAVA_HOME/bin/java" -jar mcp-app/target/mcp-opensearch.jar
# w drugim terminalu:
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18541/actuator/health          # 200
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' \
     -H 'Accept: application/json, text/event-stream' \
     -d '{"jsonrpc":"2.0","id":1,"method":"ping"}' http://127.0.0.1:18541/mcp            # 401
```

Wartości domyślne profilu są w `mcp-app/src/main/resources/application-dev.yml` (backend
`http://localhost:18081`, portal `http://localhost:18090`, prefiks indeksów `logs-dev`); każdą można nadpisać
zmienną z tabeli wyżej.

Ryzyko: `application-dev.yml` jest w jarze produkcyjnym, a profil `dev` przyjmuje JWT podpisane opublikowanymi
kluczami testowymi kontraktów i nie wymaga certyfikatu klienta. Dlatego `dev` słucha wyłącznie na adresie pętli
zwrotnej: starter odmawia startu z `dev` przy innym `server.address`, a test
`DevProfileLoopbackTest` pilnuje tego w serwerze. Nie ustawiaj `SERVER_ADDRESS` ani `MCP_BIND` przy `dev` i
nigdy nie włączaj `dev` na hoście ani w kontenerze, do którego łączy się gateway: tam działa wyłącznie
konfiguracja bazowa z mTLS.

`SPRING_PROFILES_ACTIVE=dev` wolno ustawić tylko w poleceniu z terminala, jak wyżej. Nie trafia do `.env`, szablonów
compose, jednostek systemd ani obrazu: od 3.0.0-alpha-9 starter nie startuje z `dev` w kontenerze, na Kubernetesie
i pod systemd, a `application-dev.yml` nie ustawia już TLS, mTLS ani magazynu `jti` (robi to starter).

## Uruchomienie z mcp-platform-dev (mTLS)

Konfiguracja produkcyjna na hoście, z certyfikatami i Redisem środowiska `mcp-platform-dev` (`make up` już
uruchomione). Środowisko ma certyfikat `certs/mcp-opensearch` (SAN `mcp-opensearch.internal`) i użytkownika
Redisa `mcp-opensearch`, ale nie ma mocka OpenSearch: poniżej `MCP_OPENSEARCH_URL` wskazuje adres bez usługi,
więc health i 401 działają, a wywołania narzędzi dadzą `upstream_unavailable`.

Magazyny PKCS12 i Redis są chronione hasłami z `$MCP_PLATFORM_DEV/.secrets/dev.env`, więc ten wariant wymaga
ich odczytu.

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
export MCP_PLATFORM_DEV=<workspace>/mcp-platform-dev           # ścieżka bezwzględna
export MCP_TLS_STORE_PASSWORD="$(grep '^TLS_STORE_PASSWORD=' "$MCP_PLATFORM_DEV/.secrets/dev.env" | cut -d= -f2-)"
export MCP_REDIS_PASSWORD="$(grep '^REDIS_MCP_OPENSEARCH_PASSWORD=' "$MCP_PLATFORM_DEV/.secrets/dev.env" | cut -d= -f2-)"
export MCP_TLS_KEYSTORE="$MCP_PLATFORM_DEV/certs/mcp-opensearch/keystore.p12"
export MCP_TLS_TRUSTSTORE="$MCP_PLATFORM_DEV/certs/ca/truststore.p12"
export MCP_PORT=18540 MCP_BIND=127.0.0.1 MCP_REDIS_PORT=16379
export MCP_OPENSEARCH_URL=http://localhost:18549 MCP_OPENSEARCH_ALLOW_PLAIN_HTTP=true   # bez mocka; http tylko dla mocków środowiska
export MCP_OPENSEARCH_INDEX_PREFIXES=logs-team-a,logs-team-b
export MCP_GATEWAY_JWKS_URI=https://localhost:18445/.well-known/jwks.json
export MCP_CONNECT_URL='http://localhost:18090/connections?system={system}'
"$JAVA_HOME/bin/java" -jar mcp-app/target/mcp-opensearch.jar
# w drugim terminalu (te same zmienne MCP_PLATFORM_DEV), certyfikat klienta gateway'a:
TLS=(--cacert "$MCP_PLATFORM_DEV/certs/ca/ca.crt" --resolve mcp-opensearch.internal:18540:127.0.0.1
     --cert "$MCP_PLATFORM_DEV/certs/mcp-gateway/tls.crt" --key "$MCP_PLATFORM_DEV/certs/mcp-gateway/tls.key")
curl -s -o /dev/null -w '%{http_code}\n' "${TLS[@]}" https://mcp-opensearch.internal:18540/actuator/health   # 200
curl -s -o /dev/null -w '%{http_code}\n' "${TLS[@]}" -X POST -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"ping"}' https://mcp-opensearch.internal:18540/mcp                    # 401
```

Połączenie bez certyfikatu klienta jest zrywane w handshake'u. Pełne wywołanie narzędzia wymaga gateway'a z
hosta (`mcp-gateway/scripts/run-dev-host.sh`, JWKS na `localhost:18445`) skonfigurowanego na ten serwer.

## Testy

| Klasa | Co sprawdza |
| --- | --- |
| `SearchLogsToolTest` | poświadczenie z C3 w obu wywołaniach OpenSearch; postać na drucie (ścieżka i zapytanie percent-encoded, przecinek `p,p-*` jako `%2C`); ciało `_search` (`multi_match`, zakres, filtr `_index`, bez agregacji), trafienie indeksu z tagiem `restricted` usuwane przez `ResultFilter`; `UntrustedText` bez escapowania, tylko pola z konfiguracji, odrzucenie trafień spoza prefiksów; `readOnlyHint` i schemat; brak poświadczenia, 401, `service-account`, schemat `ApiKey` -> `credential_required`; `token-exchange` jak `user-secret`; alias poza zakres, alias do indeksu ukrytego, indeks bez uprawnień, brakujący i ukryty -> ten sam `not_found_or_denied`; poprawna nazwa spoza prefiksów wygląda jak brakujący indeks; data stream; 16 złych wzorców, naruszenia schematu (C10, bez echa wartości), druga linia walidacji w narzędziu; nazwa nieznanego argumentu z podrobioną linią JSON nie trafia do logu, a wywołanie jest audytowane; nieczytelna odpowiedź, przekierowanie; błąd 500 bez treści w wyniku i logu |
| `GetDocumentToolTest` | odczyt z C3, tylko pola z konfiguracji, `UntrustedText`; kodowanie `id` na drucie (`a:b@c+d=e`); alias czytany przez konkretny indeks (kolejność wywołań), alias z filtrem lub routingiem i alias do indeksu ukrytego -> `not_found_or_denied`; alias wielu indeksów i data stream -> `invalid_argument`; brak poświadczenia, 401; brakujący dokument = indeks bez uprawnień = brakujący = ukryty = alias poza zakres = indeks poza prefiksami; złe `index` i `id` (C10, bez echa), brakujący i nadmiarowy argument; nieczytelna odpowiedź, przekierowanie, przekroczony czas, odpowiedź ponad limit; wzorzec `index` pochodzi z kontraktów |
| `GatewayOnlyAccessTest` | bez JWT 401 (`tools/call`, `ping`, `initialize`), JWT dla innego serwera, innego narzędzia, wariant serwisowy i przeterminowany -> 401; bez JWT tylko health |
| `ResourcePolicyTest` | indeks z tagiem `restricted` -> `not_found_or_denied` identyczny jak brakujący; `access_denied` bez grupy odczytu (OpenSearch nietknięty); brak grantów -> odmowa bez żadnego zapytania; zapytanie ograniczone do przyznanego prefiksu; link zawężający i pusty przekrój linku z grantami; `p*` nie dochodzi do sąsiada (`logs-team-ab`) |
| `AggregateLogsToolTest` | liczy tylko indeksy dopuszczone polityką; alias we wzorcu -> `invalid_argument`; alias poza zakresem -> `not_found_or_denied`; złe argumenty; schemat |
| `ManifestTest` | `GET /mcp-manifest` zgodny z regułami gateway'a, definicje i `definitionHash` równe `tools/list`, `resourceRefs` tylko w `get_document`, tytuły, zamknięte schematy |
| `QueryScopeIndexScopeProviderTest` | granty `p` -> `p,p-*`, `payments-old` poza zakresem; pusty zakres; przecięcie z linkiem i z konfiguracją |
| `ProductionConfigurationTest`, `DevProfileLoopbackTest` | mTLS `need` poza `dev`; `https` poza `dev` (wyjątek: jawne `allow-plain-http`); adres bez userinfo, query i fragmentu; `dev` tylko na pętli zwrotnej |
| `OpenSearchServiceTest` | granica prefiksu na `-`, wyrażenie `p,p-*`, odrzucanie trafień i dokumentów spoza zakresu, alias z jednym celem poza zakresem, cel aliasu ukryty albo niezwrócony przy drugim `_resolve`, alias z filtrem w `get_document` (bez Springa) |

`StubBackend` odpowiada kształtami REST API OpenSearch 2.x (`_resolve/index`, `_alias`, `_search`, `_doc`), łącznie z
aliasami (także filtrowanym i prowadzącym do indeksu ukrytego), data streamem, 403 OpenSearch Security oraz
nieczytelną, przekierowaną, wolną i za dużą odpowiedzią; zapisuje ścieżki w postaci z drutu. Nie jest to prawdziwy klaster (STATUS.md).

Nowe narzędzie dostaje test w tym samym stylu: klasa dziedziczy po `ServerTestSupport`, odpowiedź systemu
dopisuje się w `StubBackend`.

## Dokumentacja

- `docs/jak-napisac-narzedzie.md`: zasady i kolejność kroków przy nowym narzędziu.
- `docs/architecture/overview.md`: architektura i decyzje.
- `docs/security/SECURITY.md`: niezmienniki bezpieczeństwa w tym serwerze.
- `docs/api/tools-catalog.md`: rejestr narzędzi.
- `docs/operations/deployment-vm-systemd.md`, `deploy/`, `Dockerfile`: wdrożenie.
