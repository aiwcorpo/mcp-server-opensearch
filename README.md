# mcp-opensearch: serwer MCP platformy

Serwer MCP dla systemu `opensearch`, zbudowany na `mcp-platform-starter` 3.0.0-beta-13 (Java 25, Spring Boot
4.1.0, Spring AI 2.0.0, kontrakty `mcp-contracts` 6.2.1). Stoi wyłącznie za gateway'em platformy: przyjmuje
ruch tylko po mTLS z wewnętrznym JWT gateway'a (C2), a do systemu docelowego woła z poświadczeniem
użytkownika, które gateway dołącza do każdego żądania (C3). Bez poprawnego JWT odpowiada 401, nie ma trybu
anonimowego.

## Dla kogo

Dla administratora, który podłącza własny klaster OpenSearch (self-managed, wtyczka Security) do platformy MCP, i dla
autora, który dopisuje narzędzia (`docs/jak-napisac-narzedzie.md`). Serwer tylko czyta: nie ma narzędzia, które zmienia
dane albo ustawienia klastra, ani narzędzia przyjmującego zapytanie, ścieżkę API, SQL czy PPL od modelu. Dokumentacja
platformy (gateway, polityki, portal): repozytorium `mcp-docs` organizacji `aiwcorpo`; tu jest tylko to, co dotyczy tego serwera.

### Wspierane wersje

| Produkt | Stan | Jak jest sprawdzone |
| --- | --- | --- |
| OpenSearch 2.0 do 2.x (najnowsza 2.19) | wspierany | fixture `opensearch-2.19` w `StubBackend` |
| OpenSearch 3.x (3.0 do 3.9) | wspierany | fixture `opensearch-3.x` |
| OpenSearch 1.x, Elasticsearch 7.10 i forki bez `version.distribution: opensearch`, AWS OpenSearch Serverless | tryb `degraded`: `search_logs`, `aggregate_logs`, `count_logs`, `get_document`, `get_index_mapping` działają (to wspólne API z Elasticsearch 7.10); `list_indices`, `validate_query` i `get_cluster_health` odpowiadają `upstream_unavailable` | fixture `elasticsearch-7.10` sprawdza tylko klasyfikację sondy |
| OpenSearch 4.x i nowsze | jak wyżej, do czasu sprawdzenia | |

Minimalna wspierana wersja to OpenSearch 2.0. Wersja jest czytana z `GET /` przy starcie i co 5 minut (patrz "Wersje
OpenSearch i tryb degraded"). Fixtures w `mcp-app/src/test/resources/opensearch-fixtures/` są napisane według
udokumentowanych kształtów odpowiedzi, nie nagrane z klastra (nie było klastra ani zgody na pobranie obrazów); wymieniamy je na
nagrania, gdy będzie klaster testowy.

## Szybki start

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"      # macOS; na Linuksie katalog JDK 25
./mvnw verify                 # build, testy przez TestGateway i atrapę backendu; jar: mcp-app/target/mcp-opensearch.jar
```

Testy nie potrzebują Dockera ani środowiska `mcp-platform-dev`: `TestGateway` z `mcp-platform-test` wystawia
JWKS z kluczy testowych kontraktów i podpisuje wewnętrzne JWT, a `StubBackend` (test) udaje API systemu.

## Moduły i miejsce każdej rzeczy

| Moduł | Co w nim jest | W tym serwerze |
| --- | --- | --- |
| `mcp-core` | domena i porty, czysta Java | `IndexScope` (prefiksy, granica na `-`), `LogQuery`, `LogHit`, `LogPage`, `SearchAfter`, `IndexDocument`, `IndexMapping`, `QueryCheck`, `ClusterHealth`, `OpenSearchFeatures`, wyjątki domeny; port wejściowy `OpenSearchUseCase`, porty wyjściowe `OpenSearchPort`, `ScopedIndices` i `IndexScopeProvider` |
| `mcp-application` | przypadki użycia (bez Springa) | `OpenSearchService`: prefiks z argumentu wybiera spośród konkretnych indeksów zakresu, odrzucanie wyników spoza zakresu, strony listy indeksów |
| `mcp-integration` | adaptery wyjściowe do systemu | `OpenSearchAdapter` (REST, poświadczenie z C3, timeouty, bez przekierowań, `https` poza `dev`, budowa zapytania), `BoundedBodyInterceptor` (limit rozmiaru odpowiedzi), `BackendResponseException` (odpowiedź bez treści w komunikacie), `OpenSearchCapabilityProbe` (`GET /` przy starcie), `QueryScopeIndices`, `QueryScopeIndexScopeProvider`, `UntrustedValues` (spłaszczenie wartości do wpisów, normalizacja, limity), właściwości `opensearch.*` |
| `mcp-tools` | narzędzia MCP, powierzchnia publiczna | `OpenSearchTools` (dziewięć narzędzi, `@ArgumentLimits` na argumentach, kursory `CursorCodec`), `OpenSearchToolErrors` (wyjątki domeny -> kody C10) |
| `mcp-app` | bootstrap, wiring, jedyny jar, testy end-to-end | `McpServerApplication`, `McpToolsConfiguration` (lista narzędzi przez `SchemaLimitedToolCallbacks.of(...)` ze startera), `ApplicationWiringConfiguration` |

Zależności idą do środka: `mcp-tools` -> `mcp-application` -> `mcp-core` <- `mcp-integration`; `mcp-app` składa
całość. Resolver zasobów (`@ResourceRef`, starter beta) dojdzie w `mcp-integration` obok adaptera, bo atrybut
`tags` indeksu pochodzi z systemu.

## Narzędzia

Dziewięć narzędzi, wszystkie `READ_ONLY` z akcją `read`. Poświadczenie każdego: `token-exchange`, `Authorization: Bearer`
(token od brokera, używany w jednym żądaniu, bez cache). Uprawnienie (C9): grupa `mcp-opensearch-read`; narzędzia
działające na indeksach wymagają też co najmniej jednego zakresu `mcp-opensearch-idx-<prefiks>`.

| Narzędzie (tytuł) | Ryzyko | Akcja | Uprawnienie | `resourceRefs` w manifeście (argument) / filtr wyniku | Co robi |
| --- | --- | --- | --- | --- | --- |
| `search_logs` ("Szukaj w logach") | `READ_ONLY` | `read` | `mcp-opensearch-read` + zakres indeksów | argument: brak; filtr wyniku: `OpenSearch::Index` na `items[].index` | wyszukiwanie w logach prefiksu w zakresie czasu, strony przez `cursor` |
| `aggregate_logs` ("Zlicz logi według pola") | `READ_ONLY` | `read` | jak wyżej | brak; indeksy filtrowane polityką przed zapytaniem | liczba dokumentów według `level`, `service` albo `host` |
| `count_logs` ("Policz logi") | `READ_ONLY` | `read` | jak wyżej | brak (jak `aggregate_logs`) | liczba dokumentów spełniających filtr |
| `get_document` ("Pobierz dokument z logów") | `READ_ONLY` | `read` | jak wyżej | argument `index`: `OpenSearch::Index` | jeden dokument po indeksie i `id` |
| `list_indices` ("Wylistuj indeksy") | `READ_ONLY` | `read` | jak wyżej | argument: brak; filtr wyniku: `OpenSearch::Index` na `items[].name` | indeksy prefiksu ze zdrowiem (bez liczby dokumentów i rozmiaru: statystyki indeksu nie respektują DLS); strony przez `cursor` |
| `get_index_mapping` ("Pokaż mapowanie indeksu") | `READ_ONLY` | `read` | jak wyżej | argument `index`: `OpenSearch::Index` | pola indeksu z typami |
| `validate_query` ("Sprawdź zapytanie do logów") | `READ_ONLY` | `read` | jak wyżej | brak | czy OpenSearch przyjmuje zapytanie serwera i jak je czyta |
| `get_cluster_health` ("Pokaż zdrowie klastra") | `READ_ONLY` | `read` | `mcp-opensearch-read`; wynik dotyczy całego klastra, więc polityka powinna go dawać osobnej grupie | brak | status, liczba węzłów i shardów, bez nazw |
| `get_backend_info` ("Pokaż wersję i zdolności OpenSearch") | `READ_ONLY` | `read` | jak `get_cluster_health` | brak | wersja z sondy i dostępne funkcje; nie pyta OpenSearch |

Nazwy widziane przez klienta mają prefiks `opensearch__` (dokłada gateway, C5). Model podaje wartości (tekst, poziom, usługa,
pole grupowania), nigdy zapytania: `multi_match` z operatorem `and` po polach z konfiguracji, `term` dla poziomu i usługi,
zakres czasu, filtr `_index` do prefiksów zakresu i jedną agregację `terms` na polu z konfiguracji buduje serwer.

Argumenty (schematy są zamknięte, `additionalProperties: false`; limity trafiają do `inputSchema` i manifestu):

- `index_prefix`: prefiks `p` z kontraktu `OpenSearchIndexPrefix`, bez wieloznaczników; obejmuje indeks `p` i `p-...` w zakresie grantów.
- `text`: 1 do 512 znaków, słowa, które muszą wystąpić; operatory i cudzysłowy to zwykły tekst. Wymagany w `search_logs`, opcjonalny w pozostałych.
- `level` (`TRACE` do `FATAL`), `service` (nazwa), `from`, `to` (ISO-8601 z przesunięciem, `to > from`, zakres do `MCP_OPENSEARCH_MAX_RANGE`).
- `size`: `search_logs` 1 do 100 (domyślnie 20), `aggregate_logs` 1 do 50 (10), `list_indices` 1 do 50 (25).
- `cursor`: `nextCursor` poprzedniej strony tego samego zapytania (`search_logs`, `list_indices`), do 1024 znaków `[A-Za-z0-9_.-]`.
- `index` i `id` w `get_document` oraz `index` w `get_index_mapping`: jeden konkretny indeks (nie alias, nie data stream), `id` według `^[A-Za-z0-9_-][A-Za-z0-9_.:@+=-]{0,255}$`.
- `group_by` w `aggregate_logs`: `level`, `service`, `host`.

Wyniki:

- `search_logs`, `list_indices`: `PagedResult` platformy, `{items, nextCursor, truncated, omitted}`; bez liczników i bez `total`
  (liczba z systemu zawierałaby to, czego wywołujący nie widzi). Element `search_logs`: `{index, id, timestamp, data, truncated}`,
  `data` to wpisy `{name, path, value}` z `path` i `value` jako `UntrustedText`. Element `list_indices`: `{name, health}`
  (`health` jest `null`, gdy OpenSearch go nie podał). Liczby dokumentów i rozmiaru nie ma celowo: `docs.count` i `store.size` to statystyki całego indeksu,
  DLS ich nie dotyczy, więc zdradzałyby dokumenty, których wywołujący nie widzi.
- `aggregate_logs`: `{indexPrefix, from, to, groupBy, buckets: [{key, count}], otherDocuments}`, `key` jako `UntrustedText`; `otherDocuments` to dokumenty w grupach spoza listy (agregacja `terms`
  zwraca największe grupy, a przy dużej liczności pola `host` liczby grup są przybliżone).
- `count_logs`: `{indexPrefix, from, to, count, exact}`; `exact: false`, gdy OpenSearch podał dolne ograniczenie albo żądanie przekroczyło limit czasu.
- `get_document`: `{index, id, data, truncated}`.
- `get_index_mapping`: `{index, fields: [{path, type}], truncated}`; `path` jako `UntrustedText` (nazwy pól wymyśla ten, kto loguje),
  tylko pola, których serwer używa (czas, pola zapytania, wyniku, poziom, usługa, host, tiebreaker, razem z ich polami wielokrotnymi, np. `message.keyword`); reszta schematu
  logów zostaje w OpenSearch. Zagnieżdżone pola jako ścieżki z kropką.
- `validate_query`: `{valid, checked, invalidIndices, explanation}`; `explanation` to zapis Lucene pierwszego indeksu jako `UntrustedText`; tekst błędu
  z OpenSearch nie jest przekazywany.
- `get_cluster_health`: `{status, timedOut, nodes, activeShards, relocatingShards, initializingShards, unassignedShards, activeShardsPercent}`.
- `get_backend_info`: `{state, version, features, detail}`.

Wynik `search_logs` nie przekracza 1 MB (budżet 900000 B szacowanego JSON); po przekroczeniu `truncated: true`, a `nextCursor`
wskazuje miejsce po ostatnim zwróconym trafieniu.

Błędy narzędzi: `not_found_or_denied` (brak indeksu lub dokumentu, 403 z OpenSearch, brak grantów, indeks poza grantami, indeks z tagiem
`restricted`, alias, data stream, indeks ukryty; treść to stała platformy), `access_denied` (brak grupy `mcp-opensearch-read`),
`credential_required` (brak poświadczenia, 401 z OpenSearch, schemat inny niż `Bearer`), `invalid_argument` (zły argument albo
kursor, prefiks obejmujący więcej niż 100 indeksów), `upstream_unavailable` (system nie odpowiada albo narzędzie jest wyłączone
w trybie `degraded`), `internal_error` (także typ poświadczenia spoza `[token-exchange]`). Brak grantów, prefiks bez indeksu, poza
zakresem albo z samymi indeksami `restricted` to w narzędziach wyszukujących i liczących pusty wynik (zero w `count_logs`), ten sam dla wszystkich
tych przyczyn. Szczegóły: `docs/api/tools-catalog.md`.

Narzędzi, które nie powstały, a które badanie API zalecało: `list_aliases` i `resolve_alias` (potrzebują typu zasobu `OpenSearch::Alias`,
CC-214; alias o nazwie z zakresu może prowadzić do indeksów innego zespołu, więc serwer nie pokazuje aliasów w żadnej postaci), `field_caps`
(pokrywa się z `get_index_mapping`), `run_ppl`, `sql`, `_msearch` i narzędzie "dowolne API" (poza modelem bezpieczeństwa platformy).

### Ograniczenia, o których warto wiedzieć

- **Data streamy nie są obsługiwane.** Indeksy zapasowe `.ds-*` mają wiodącą kropkę, a strumień i alias nie są nigdy celem zapytania. Klaster, który trzyma logi w data streamach,
  da puste wyniki; obejściem jest alias na zwykłe indeksy po stronie klastra albo osobna decyzja (CC-214 dotyczy aliasów).
- **Filtr daty w nazwie indeksu.** Zakres czasu odcina indeksy `p-YYYY.MM.DD` i `p-YYYY.MM` z datą poza `from`-`to` poszerzonym o dobę. Indeks rotowany według czasu przyjęcia,
  z logami spóźnionymi o ponad dobę, wypada z zapytania, więc `count_logs` i `aggregate_logs` zwrócą mniej bez sygnału. Indeksy bez daty w nazwie są zawsze brane.
- **Limity czasu.** Ciało `_search` ma `timeout` równy 80% `read-timeout`, żądanie `cancel_after_time_interval` 90%; `search_logs` czyta najwyżej 5 stron w czasie `MCP_OPENSEARCH_CALL_BUDGET`.
  Odpowiedź OpenSearch może mieć do 4 MB (`max-response-size`); wynik narzędzia i tak jest cięty do 900000 B.
- **Strona bez kursora mimo dalszych danych.** Gdy pozycja ostatniego trafienia nie mieści się w kursorze, wynik ma w `omitted` kod `cursor_unavailable`.
- **`get_cluster_health` i `get_backend_info` pokazują dane całego klastra** (skalę shardów i węzłów, dokładną wersję). Paczka polityk (ws-09) da im osobną regułę; do tego czasu
  grupa `mcp-opensearch-read` obejmuje je tak jak resztę narzędzi `READ_ONLY`.

### Stronicowanie

`search_logs` stronicuje przez `search_after`, a `list_indices` przez nazwę ostatniego indeksu. Kursor to `CursorCodec` startera:
szyfrowany (AES-256-GCM, od beta-13) i uwierzytelniony, związany z `sub` wywołującego, narzędziem i odciskiem zapytania (prefiks, tekst, filtry,
zakres czasu, `size`), ważny 15 minut. Kursor innego użytkownika, innego narzędzia, zmienionego zapytania, sfałszowany i przeterminowany kończy się tym samym
`invalid_argument`. Kursor niesie tylko pozycję; zakres wywołującego liczy się od nowa na każdej stronie, a politykę stosuje się do
każdej strony, więc kursor niczego nie poszerza. Pozycja pochodzi zawsze z ostatniego trafienia, które wywołujący dostaje: serwer odrzuca
trafienia, których polityka nie dopuszcza (indeks z tagiem `restricted`), zanim zbuduje kursor, i dopełnia stronę kolejnymi stronami OpenSearch
(najwyżej 5 odczytów na wywołanie). Gdy w tych 5 odczytach nie zostanie żadne dozwolone trafienie, kursor wskazuje koniec ostatniej odczytanej
strony i nic nie jest zwracane; to jedyny przypadek, w którym pozycja może należeć do ukrytego dokumentu, a szyfrowanie kursora zamyka jej odczyt
przez klienta. Strona może być krótsza niż `size` tylko przy końcu danych albo po wyczerpaniu odczytów.

`search_logs` sortuje po polu czasu malejąco, a potem po polu o unikalnej wartości w każdym dokumencie (`MCP_OPENSEARCH_TIEBREAKER_FIELD`,
np. `event.id`: pole `keyword` albo liczba). Bez drugiego klucza dokumenty o tym samym czasie ginęłyby na granicy stron. To nie może być `_id`
(dokumentacja OpenSearch odradza: brak doc values, unikalny tylko w obrębie indeksu), więc serwer odmawia startu z `_id` albo innym polem
zaczynającym się od `_`. Dopóki pole nie jest ustawione, stronicowanie jest wyłączone: `search_logs` zwraca pierwszą stronę (`nextCursor`
zawsze `null`) i działa jak przed 6.2. Pole musi istnieć w mapowaniu; sprawdza to `get_index_mapping` (pierwsze uruchomienie, niżej).
`list_indices` nie wymaga ustawienia.

Klucz kursorów (`MCP_CURSOR_KEY`, Base64, co najmniej 32 bajty) musi być taki sam w każdej instancji; bez niego instancja losuje własny
i ostrzega w logu, a kursor z jednej instancji odrzuca druga i ta sama po restarcie.

### Wersje OpenSearch i tryb degraded

Starter uruchamia `OpenSearchCapabilityProbe` po starcie i co 5 minut (`aiwcorpo.mcp.platform.capability.interval`); sonda woła `GET /` bez
użytkownika i czyta tylko `version.number` i `version.distribution`.

| Odpowiedź `GET /` | Stan | Funkcje |
| --- | --- | --- |
| `distribution: opensearch`, wersja 2.0 do 3.x | `supported` | wszystkie (`OpenSearchFeatures.TESTED`) |
| `distribution` inna albo brak, wersja spoza 2.0 do 3.x | `degraded` | `search`, `count`, `get_document`, `mapping`, `search_after` |
| 401 lub 403 | `degraded`, bez wersji | jak wyżej |
| brak wersji, nieczytelna odpowiedź, błąd sieci, 5xx; też przed pierwszą odpowiedzią sondy | `unavailable` | brak: każde narzędzie, które woła OpenSearch, odpowiada `upstream_unavailable` bez wywołania (`get_backend_info` działa) |

Klaster z wtyczką Security zwykle odpowiada 401 na anonimowe `GET /`. Serwer nie trzyma własnego poświadczenia (poświadczenia
użytkowników przychodzą od gateway'a), więc sonda nie może się zalogować i stan to `degraded`: nic się nie psuje, ale `list_indices`,
`validate_query` i `get_cluster_health` odmawiają. Jeśli klaster jest OpenSearch 2.x lub 3.x, ustaw `MCP_OPENSEARCH_ASSUME_TESTED_VERSION=true`:
przy odpowiedzi 401 lub 403 sonda zgłosi wtedy `supported` z wersją `unknown` (odpowiedź z inną dystrybucją, np. Elasticsearch, nadal jest
`degraded`). To oświadczenie operatora, nie odczyt; wersję sprawdzisz sam (niżej, "Pierwsze uruchomienie"). Poświadczenie serwisowe dla sondy
byłoby decyzją brokera (CC-215).

Do pierwszej odpowiedzi sondy (zwykle sekundy po starcie) system jest `unavailable`, więc wszystkie narzędzia poza `get_backend_info` odpowiadają `upstream_unavailable`;
sonda ponawia się po `capability.retry-interval` (30 s), gdy system nie odpowiada.
Stan widać w logu (`backend capability: probe=opensearch state=...`), w metryce `mcp_backend_capability{probe,state}`, w szczegółach
`/actuator/health` (`backendCapability`) i w narzędziu `get_backend_info`. Zdarzenia audytu zmiany stanu nie ma (CC-208).

Żadne wywołanie serwera nie różni się między 2.x a 3.x: wszystkie endpointy istnieją od OpenSearch 1.x, więc nie ma adapterów per wersja.

### Polityki, zakres i klasyfikacja (M3)

- `get_document.index` ma `@ResourceRef(type = "OpenSearch::Index")`. Resolver (`OpenSearchIndexResolver`) pyta
  OpenSearch poświadczeniem użytkownika, w zakresie grantów, i opisuje indeks, który OpenSearch faktycznie podaje
  (tylko konkretny indeks z listy nazw zakresu): `name` i `tags`. Brak, zakaz i odmowa polityki to ten sam `not_found_or_denied`.
- `tags` (atrybut obowiązkowy) pochodzą z konfiguracji, bo OpenSearch nie ma tagów: `opensearch.scope.index-tags`
  (lista `{prefix, tags}`; prefiks obejmuje `p` i `p-...`; w środowisku `OPENSEARCH_SCOPE_INDEXTAGS_0_PREFIX`,
  `OPENSEARCH_SCOPE_INDEXTAGS_0_TAGS_0`). Indeks bez reguły ma `tags: []`. Paczka polityk zabrania `restricted`.
- Zakres wyszukiwania z `QueryScope`: grupy `mcp-opensearch-idx-<prefiks>` (mapowanie w paczce) przecięte z `MCP_OPENSEARCH_INDEX_PREFIXES`. Kolejne `p` dają wyrażenie
  `p,p-*` (nigdy `p*`: `payments-old` nie wchodzi do zakresu `p`). To wyrażenie jest jedynym wzorcem, jaki serwer
  wysyła do OpenSearch, i tylko do `GET _resolve/index`. Gdy `QueryScope.nothingToQuery()` (brak grantów w prefiksach
  serwera), `search_logs` i `aggregate_logs` zwracają pusty wynik bez żadnego zapytania, a
  `get_document` `not_found_or_denied`: puste wyrażenie indeksów oznaczałoby wszystkie indeksy.
- `search_logs`: `LogHitView.index` to `@ResourceRef`, więc starter po wykonaniu usuwa trafienia z indeksów, których
  polityka nie dopuszcza (np. tag `restricted`). Wynik nie ma liczników (`returned`, `total`).
- `aggregate_logs`: liczby nie dają się poprawić po fakcie, więc do zapytania trafiają tylko konkretne indeksy z
  `scope.opensearchSearchTarget(odpowiedź)` (bez aliasów i strumieni, w zakresie), które polityka dopuszcza
  (`ResourcePolicy.allows`) i które obejmuje prefiks z argumentu. Alias nigdy nie jest liczony, więc narzędzie nie
  ma już odmowy dla aliasów (`QueryScope.requireNoAliases` nie jest używane). Reguła wyłączająca `aggregate_logs` w
  paczce polityk (`mcp-policies`) zostaje do sygnału koordynatora.

### Zakres indeksów

Prefiks `p` obejmuje indeks `p` i indeksy `p-...`, nigdy `p...` bez myślnika (`logs-team-a` nie obejmuje
`logs-team-ab`; ta sama reguła co `OpenSearchIndexPrefix` w kontraktach i zakresy Cedar). Do OpenSearch nie trafia
żaden wzorzec z argumentu: model podaje prefiks, a serwer wybiera nim spośród nazw indeksów, które istnieją i mieszczą
się w zakresie. Kolejne kroki `search_logs`:

1. Argument sprawdza starter względem `inputSchema` (wzorzec `OpenSearchIndexPrefix` z kontraktów, bez `*`, przecinka,
   `:`, `<`, wiodącej kropki, `_all`; `invalid_argument` bez nazwy i wartości), potem narzędzie.
   Argument nazywa się `index_prefix`.
2. Zakres wywołania wynika z grantów (wyżej); pusty zakres kończy wywołanie pustym wynikiem.
3. `GET _resolve/index/<wyrażenie zakresu>?expand_wildcards=open` (`QueryScope.OPENSEARCH_RESOLVE_PARAMETERS`);
   odpowiedź 404 to pusta odpowiedź (`QueryScope.EMPTY_RESOLVE_RESPONSE`). Odpowiedzi nie ma w żadnym cache: każde
   wywołanie narzędzia pyta od nowa (okno między rozwiązaniem a zapytaniem trwa tyle co cache). `opensearchSearchTarget`
   zostawia indeksy w zakresie, bez aliasów i strumieni danych i o nazwach zgodnych z `OpenSearchIndexName`; serwer
   dodatkowo odrzuca indeksy z atrybutem `hidden` (nazwa dokładna może do takiego dojść) i spoza
   `MCP_OPENSEARCH_INDEX_PREFIXES`. Pusta lista to "nie pytaj": pusty wynik.
4. Prefiks z argumentu zostawia nazwy `p` i `p-...`; zakres czasu `from`-`to` zostawia indeksy, których data w nazwie
   (`p-YYYY.MM.DD` dzień, `p-YYYY.MM` miesiąc) zachodzi na ten zakres poszerzony o dobę z każdej strony, a indeksy bez daty (albo z datą, która nie
   istnieje) zostają zawsze. Data w nazwie jest czytana jako UTC; margines doby pokrywa klaster, który rotuje indeksy w innej
   strefie; filtr daty tylko odejmuje z listy zakresu. Polityka (`ResourcePolicy.allows`, np. tag `restricted`) usuwa
   kolejne. Po tych filtrach więcej niż 100 nazw albo więcej niż 3500 znaków ścieżki (nazwy plus przecinki jako `%2C`, żeby
   zmieścić się w 4096 bajtach linii żądania OpenSearch) to `invalid_argument` ("narrow the date range").
5. `POST <nazwa1,nazwa2,...>/_search` z ciałem zbudowanym przez serwer: `multi_match` (operator `and`) po polach z
   konfiguracji, opcjonalne `term` poziomu i usługi, zakres czasu, filtr `_index` do prefiksów, `_source.includes` z
   konfiguracji, `track_total_hits: false`.
6. Trafienia z indeksów spoza zakresu są odrzucane; z dokumentu zostają tylko pola z `MCP_OPENSEARCH_RESULT_FIELDS`.

`get_document` pyta tym samym `_resolve/index` i przepuszcza indeks przez `scope.resolveIndexArgument`: alias,
data stream, indeks spoza zakresu i nieznany to `not_found_or_denied`; dokument jest czytany z konkretnego indeksu.

Serwer nie używa `_msearch`, `_mget`, skryptów, `_count`, `_alias` ani `_search?q=`. Jedyna agregacja to `terms` w
`aggregate_logs`. Limity 100 nazw i 3500 znaków wynikają z długości ścieżki; przy dobowych indeksach zakres czasu (do `MCP_OPENSEARCH_MAX_RANGE`, 7 dni) zwykle go nie przekracza.

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
głębokość 6, dalej `truncated: true` w trafieniu. Wynik `search_logs` ma budżet 900000 B szacowanego JSON (platforma
odrzuca wynik ponad 1 MiB): po jego przekroczeniu reszta trafień jest pomijana i `truncated: true` w wyniku.
`index` to nazwa z zakresu, `id` to napis tylko wtedy, gdy ma kształt przyjmowany przez `get_document`
(w przeciwnym razie `null`), `timestamp` tylko gdy wygląda jak data lub liczba. Pola puste są `@Nullable` w
schemacie wyniku. Wynik nie ma liczby wszystkich trafień.

### DLS/FLS w OpenSearch: druga warstwa

Serwer woła OpenSearch poświadczeniem użytkownika, więc OpenSearch Security stosuje jego role: uprawnienia do
indeksów, DLS (filtr dokumentów) i FLS (maskowanie pól). Kontrole serwera (prefiksy, aliasy, pola wyniku) to
pierwsza warstwa, która działa także wtedy, gdy role w OpenSearch są za szerokie; DLS/FLS to druga, która
działa, gdy serwer czegoś nie przewidzi. Wymagania wobec ról użytkowników: `indices:admin/resolve/index`,
`indices:data/read/search` /
`indices:data/read/get` na ich wzorcach; zalecane
`do_not_fail_on_forbidden: true`, żeby wzorzec obejmujący indeks bez uprawnień zwracał resztę zamiast 403.
Poświadczenie `service-account` (i każdy typ poza `token-exchange`) jest odrzucane: zastąpiłoby DLS/FLS użytkownika uprawnieniami konta
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

## Rejestracja, tryb dev i metryki (M4)

- Manifest do rejestracji w gateway'u powstaje w buildzie: `ManifestExport` (exec-maven-plugin, faza `package`,
  `manifest.export.skip=false` w `mcp-app/pom.xml`) buduje go z `application.yml` bez profilu i zapisuje
  `mcp-app/target/mcp-manifest.json` z przeglądanego commitu, także przy `-DskipTests`. CI rejestruje ten plik certyfikatem
  `mcp-registry-ci-mcp-opensearch`, nigdy odpowiedzi działającego serwera. `acceptedTypes` to `["token-exchange"]`, ustalone na stałe w `application.yml`; `CredentialTypeGuard` nie pozwala wystartować z inną wartością (także ustawioną ze środowiska).
  `ManifestTest` porównuje narzędzia tego pliku z `tools/list` działającego serwera.
- Stos dev uruchamia ten sam obraz z `SPRING_PROFILES_ACTIVE=devstack` i dwiema zgodami (`MCP_DEVSTACK_ACKNOWLEDGED`
  w środowisku procesu oraz `AIWCORPO_MCP_PLATFORM_DEVSTACK_ACKNOWLEDGED`), a przy kluczach `dev-` także
  `AIWCORPO_MCP_PLATFORM_INTERNAL_JWT_ALLOW_TEST_KEYS` i `AIWCORPO_MCP_PLATFORM_POLICY_ALLOW_TEST_KEYS`. Manifest ma
  wtedy `"environment": "dev"`, a gateway w trybie produkcyjnym go odrzuci. Zgody ustawia konfiguracja stosu, nie obraz.
- Typ poświadczenia: `X-MCP-Credential-Type` musi należeć do `accepted-credential-types`; inaczej `internal_error`
  przed Cedar, sondą i resolverem, w audycie `credential_type_mismatch`. Zbiór `[token-exchange]` nie miesza się z
  `service-account`.
- Metryka `mcp_audit_authentication_suppressed_total` (tagi `component`, `reason`) liczy zdarzenia `authentication`
  pominięte z powodu nadmiaru; poprzednia nazwa `mcp_server_audit_authentication_suppressed_total` znikła, więc alert
  powinien patrzeć na nową: `increase(mcp_audit_authentication_suppressed_total{component="server"}[5m]) > 0`.

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
| `MCP_INTERNAL_URL` | opis serwera w manifeście (`GET /mcp-manifest`) | wymagane |
| `MCP_OPENSEARCH_RESULT_FIELDS` | jedyne pola `_source` w wyniku (bez wieloznaczników) | `@timestamp,message,log.level,service.name,host.name` |
| `MCP_OPENSEARCH_MAX_RANGE` | najdłuższy zakres `to - from` | `7d` |
| `MCP_OPENSEARCH_TIEBREAKER_FIELD` | pole o unikalnej wartości w każdym dokumencie (keyword albo liczba), drugi klucz sortowania `search_logs`; puste = bez stronicowania; `_id` jest odrzucane | puste |
| `MCP_OPENSEARCH_TIEBREAKER_TYPE` | typ sortowania tiebreakera dla indeksów bez tego pola: `keyword` albo `long` (pole liczbowe) | `keyword` |
| `MCP_OPENSEARCH_CALL_BUDGET` | czas ścienny, który jedno `search_logs` może spędzić na odczytach stron OpenSearch (najwyżej 5 odczytów) | `20s` |
| `MCP_OPENSEARCH_ASSUME_TESTED_VERSION` | `true`: przy odmowie anonimowego `GET /` uznaj klaster za OpenSearch 2.x lub 3.x | `false` |
| `MCP_CURSOR_KEY` | klucz kursorów (Base64, co najmniej 32 bajty, ten sam w każdej instancji; sekret) | puste (klucz losowy, ostrzeżenie w logu) |
| `MCP_REDIS_HOST`, `MCP_REDIS_PORT`, `MCP_REDIS_USER`, `MCP_REDIS_PASSWORD` | Redis dla jednorazowego `jti` (użytkownik ACL `mcp-opensearch`, klucze `jti:mcp-opensearch:*`) | `localhost`, `6379`, `mcp-opensearch`, puste |
| `MCP_PORT`, `MCP_BIND` | port i adres nasłuchu | `8443`, `0.0.0.0` |

Endpoint MCP to `/mcp` (domyślne ustawienie startera, tak jak oczekuje gateway: `internal-url:
https://mcp-opensearch.internal:8443/mcp`). Bez JWT odpowiada tylko `/actuator/health` (i `liveness`,
`readiness`), ale TLS i tak wymaga certyfikatu klienta z CA wewnętrznego.

Serwer nie wstaje bez `MCP_GATEWAY_JWKS_URI`, `MCP_CONNECT_URL`, `MCP_OPENSEARCH_URL`,
`MCP_OPENSEARCH_INDEX_PREFIXES`, paczki polityk (`MCP_POLICY_*`) i plików TLS. W `dev` paczka pochodzi z
`http://127.0.0.1:18747/policies/main/latest.json` (klucz z `mcp-policies/keys/dev/`, ścieżka względem katalogu startu).

## Uprawnienia: konto użytkownika w OpenSearch i token

Serwer nie ma konta w OpenSearch. Każde wywołanie idzie poświadczeniem użytkownika, które broker wymienia na token dla OpenSearch
(`token-exchange`, `Authorization: Bearer`, jedno żądanie, bez cache). Dziś to jedyny typ; klaster bez JWT albo OIDC w Security nie
zadziała z tym serwerem, a obsługa Basic czeka na decyzję (CC-213).

Wymagania wobec klastra: wtyczka Security z uwierzytelnianiem JWT albo OpenID Connect, które przyjmuje token brokera (`aud`, klucze JWKS,
nagłówek, `subject_key`, `roles_key` zależą od ustawień brokera i klastra; szczegółów nie potwierdziliśmy na żywym klastrze). Token musi
uwierzytelniać tego samego użytkownika, z rolami (backend roles) mapowanymi na role OpenSearch. Zakresów OAuth poza tym nie używamy.

Uprawnienia roli użytkownika w OpenSearch (nazwy akcji według wtyczki Security; sprawdź na pierwszym uruchomieniu, bo różnią się między wydaniami):

| Narzędzie | Wywołanie OpenSearch | Uprawnienie roli |
| --- | --- | --- |
| wszystkie na indeksach | `GET _resolve/index/...` | `indices:admin/resolve/index` |
| `search_logs`, `aggregate_logs`, `count_logs` | `POST {indeksy}/_search` | `indices:data/read/search*` na indeksach |
| `get_document` | `GET {indeks}/_doc/{id}` | `indices:data/read/get` |
| `get_index_mapping` | `GET {indeks}/_mapping` | `indices:admin/mappings/get` |
| `validate_query` | `POST {indeksy}/_validate/query` | `indices:admin/validate/query` |
| `list_indices` | `GET _cat/indices/{indeksy}` | `indices:monitor/stats`, `cluster:monitor/state`, `cluster:monitor/health` (do potwierdzenia) |
| `get_cluster_health` | `GET _cluster/health` | `cluster:monitor/health` |
| sonda wersji | `GET /` (bez użytkownika) | konto nie jest potrzebne, o ile klaster pozwala na anonimowe `GET /` |

Zalecane `do_not_fail_on_forbidden: true` w roli, żeby lista indeksów z jednym zabronionym zwracała resztę zamiast 403. DLS i FLS roli
działają jako druga warstwa za kontrolami serwera.

Uprawnienia platformy (C9): grupa `mcp-opensearch-read` (wszystkie narzędzia są `READ_ONLY`) i zakresy `mcp-opensearch-idx-<prefiks>`
w paczce polityk. `get_cluster_health` i `get_backend_info` nie wskazują indeksu, a pierwszy z nich opisuje cały klaster; paczka polityk
powinna dawać je osobnej grupie (reguła po nazwie narzędzia), a nie każdemu czytelnikowi logów.

## Instalacja

Serwer jest jednym jarem (`mcp-app/target/mcp-opensearch.jar`, `./mvnw verify`, JDK 25) albo obrazem z `Dockerfile`. Linux i macOS z bash 3.2 lub
nowszym; Windows tylko przez WSL2. Artefakty platformy (`mcp-platform-bom`, kontrakty) pochodzą z GitHub Packages organizacji `aiwcorpo`
albo z `~/.m2` (opis w README `mcp-starter-lib`, "Instalacja").

VM z systemd (zalecane): `deploy/scripts/install.sh mcp-app/target/mcp-opensearch.jar`, plik środowiska z
`deploy/systemd/mcp-opensearch.env.template`, magazyny PKCS12, potem `systemctl enable --now mcp-opensearch`. Kroki, układ plików i typowe
problemy: `docs/operations/deployment-vm-systemd.md`.

Kontener: `docker build -t mcp-opensearch:<tag> .` po `./mvnw verify` (obraz `eclipse-temurin:25-jre`, użytkownik `mcp`, port 8443, mTLS).
Certyfikaty, klucze polityk i zmienne z tabeli "Konfiguracja" dostają przez montowane pliki i środowisko; profil `dev` w kontenerze jest
odrzucany przez starter. Obraz CI: `ci/run.sh image` (tag `mcp-server-opensearch:<prefiks>-<sha7>`, tylko `main`).

Manifest do rejestracji w gateway'u to plik budowy `mcp-app/target/mcp-manifest.json` (każda zmiana narzędzi zmienia `definitionHash`
i wymaga ponownego zatwierdzenia w gateway'u).

## Codzienna obsługa

- Zdrowie: `deploy/scripts/healthcheck.sh` (mTLS); stan zdolności backendu w `/actuator/health` (`backendCapability`, z JWT), w metryce
  `mcp_backend_capability{probe="opensearch",state}` i w logu przy zmianie (`backend capability: probe=opensearch state=...`).
  Stan `degraded` albo `unavailable` nie zdejmuje serwera z balancera.
- Logi i audyt C8 idą na stdout (journald); audyt to linie JSON z loggera `pl.aiwcorpo.mcp.audit`.
- Alerty: `upstream_unavailable` w audycie (OpenSearch nie odpowiada albo narzędzie wyłączone przez tryb `degraded`), `credential_required` w
  dużej liczbie (wygasły token albo klaster przestał przyjmować tokeny brokera), `invalid_argument` z kursorem (zmiana `MCP_CURSOR_KEY` albo instancje z
  różnymi kluczami).
- Zmiana indeksów, które serwer ma obejmować: `MCP_OPENSEARCH_INDEX_PREFIXES` i restart; zakres użytkownika to dalej grupy z paczki polityk.
- Aktualizacja: `deploy/scripts/update.sh <nowy.jar>` (poprzedni jar jako `.bak.<data>`), wycofanie przez przywrócenie kopii.
- Rotacja `MCP_CURSOR_KEY`: kursory żyją 15 minut, więc zmiana klucza w tej samej chwili na wszystkich instancjach kosztuje najwyżej
  odrzucone kursory.

## Pierwsze uruchomienie u klienta (tylko odczyt)

Wszystkie kroki poniżej to żądania GET i odczyty przez serwer; żaden nie zmienia klastra.

1. `GET /` klastra (anonimowo i z kontem testowym): `version.distribution` ma być `opensearch`, `version.number` 2.x albo 3.x. Odpowiedź 401
   znaczy, że sonda będzie `degraded` (ustaw `MCP_OPENSEARCH_ASSUME_TESTED_VERSION=true`, jeśli wersja się zgadza).
2. Czy Security przyjmuje token brokera: `GET /_plugins/_security/authinfo` z tokenem użytkownika testowego pokazuje użytkownika i role.
3. Po starcie serwera: `get_backend_info` (stan, wersja, funkcje).
4. `list_indices` dla prefiksu klienta: indeksy w zakresie i uprawnienie `_cat` (jeśli 403 = `not_found_or_denied`, rola nie ma uprawnień monitorowania).
5. `get_index_mapping` jednego indeksu (pokazuje tylko pola z konfiguracji): czy pola z `MCP_OPENSEARCH_TIMESTAMP_FIELD`, `..._QUERY_FIELDS`, `..._LEVEL_FIELD`, `..._SERVICE_FIELD`, `..._HOST_FIELD`
   i `..._RESULT_FIELDS` istnieją i mają typ (czas: `date`; poziom, usługa, host: `keyword`). Bez tego `search_logs` i `aggregate_logs` zwrócą pusty wynik albo błąd.
6. `validate_query` z typowym tekstem: `valid: true`; jeśli `false`, nazwy pól w konfiguracji są złe. Dla roli z DLS sprawdź, czy `explanation` nie zawiera zapytania DLS
   roli (w trybie `filter_level` objaśnienie mogłoby je pokazać); tego nie da się sprawdzić na atrapie.
7. `count_logs` z krótkim zakresem czasu, potem `search_logs` z `size` 5.
8. Stronicowanie: ustaw `MCP_OPENSEARCH_TIEBREAKER_FIELD` na pole z unikalną wartością w każdym dokumencie (dowiedz się od zespołu logowania albo z `GET {indeks}/_mapping`
   wykonanego ręcznie; `get_index_mapping` pokazuje tylko pola z konfiguracji) i sprawdź drugą stronę.
   Sortowanie po polu wymaga doc values (keyword albo liczba); `text` nie pasuje. Obciążenie klastra przy sortowaniu po tym polu sprawdź na
   małym zakresie czasu.
9. `get_cluster_health` (jeśli rola ma `cluster:monitor/health`).

## Propozycje zmian kontraktów (zapisane, nie wdrożone)

W `mcp-contracts/CONTRACT_CHANGES.md` jako `proposed`: CC-213 (Basic dla klastra bez JWT i OIDC), CC-214 (`OpenSearch::Alias` dla
`list_aliases` i `resolve_alias`), CC-215 (poświadczenie sondy wersji). Do czasu decyzji serwer ich nie implementuje.

## CI

Cała logika jest w `ci/run.sh` (`make ci` woła `ci/run.sh verify`); `.gitlab-ci.yml`, `Jenkinsfile` i `.github/workflows/ci.yml`
to cienkie nakładki, więc to samo działa lokalnie, w obrazie `maven:3.9.12-eclipse-temurin-25` i w każdym z trzech systemów.

| Krok | Co robi |
| --- | --- |
| `ci/run.sh verify` (domyślny) | `hygiene`, `mvnw clean verify`, potem kontrola, że każdy moduł z `CI_TEST_MODULES` uruchomił testy (pominięte nie liczą się) i że powstał `mcp-app/target/mcp-manifest.json` |
| `ci/run.sh hygiene` | cudzysłowy, spacje i wielokropek spoza ASCII oraz ślady narzędzi w źródłach (perl czyta przez `<<>>`) |
| `ci/run.sh image` | `docker build` jaru z `verify` jako `mcp-server-opensearch:<prefiks>-<sha7>` (prefiks z `CI_IMAGE_TAG_PREFIX`, domyślnie `m6`); tylko gałąź `main`, wymaga demona Dockera |

Inny argument kończy się kodem 2. Merge request i pull request nie dostają sekretów ani gniazda Dockera: testy nie
potrzebują kontenerów, a rejestr Maven z artefaktami platformy (`MCP_MAVEN_REPOSITORY_URL`, zwykła zmienna) jest wtedy
czytany anonimowo (`.ci/settings-anonymous.xml`). Dane dostępu (`MCP_MAVEN_REPOSITORY_USER`, `_PASSWORD`; w GitLabie
zmienne Protected i Masked, w Jenkinsie poświadczenie `mcp-maven-registry`) widzi tylko gałąź `main`, na której działa też
krok `image` (runner z gniazdem Dockera; w GitLabie tag `docker-socket` musi należeć do runnera chronionego (protected),
inaczej gniazdo trafiłoby w ręce merge requestu; w GitHubie sekrety są w Environment `ci-main`). Moduły z wymaganymi
testami: `CI_TEST_MODULES` (domyślnie `mcp-application mcp-integration mcp-tools mcp-app`), dolna granica dla `mcp-app`: `CI_APP_MIN_TESTS` (20).
Rejestracja manifestu w gateway'u (`PUT` z certyfikatem `mcp-registry-ci-mcp-opensearch`) jest osobnym krokiem pipeline'u
serwera, bo wymaga sekretu, którego repo nie zawiera.

## Uruchomienie lokalne bez środowiska

Profil `dev` (musi być jedynym aktywnym profilem): zwykłe HTTP na 127.0.0.1, `jti` w pamięci, bez Redisa,
certyfikatów i sekretów. Gateway'a nie ma, więc każde żądanie z JWT kończy się 503 (JWKS niedostępny); ten tryb
służy do sprawdzenia, że serwer wstaje i odrzuca ruch bez JWT. Narzędzia sprawdzają testy (`./mvnw verify`).

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"      # macOS; na Linuksie katalog JDK 25
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
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"      # macOS; na Linuksie katalog JDK 25
export MCP_PLATFORM_DEV="$HOME/src/mcp-platform-dev"        # ścieżka bezwzględna do katalogu mcp-platform-dev
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
| `SearchLogsToolTest` | `_search` idzie na listę nazw (ścieżka bez wzorca, bez aliasu, tagu `restricted` i sąsiada), jedyny wzorzec to wyrażenie zakresu w `_resolve/index`, które jest powtarzane i nie reużywane; token wymiany jako `Bearer` w każdym żądaniu; typ poświadczenia spoza `[token-exchange]` -> `internal_error` bez wywołania OpenSearch; schemat `ApiKey` i `Basic` -> `credential_required`; ciało `_search`; `UntrustedText` bez escapowania, tylko pola z konfiguracji; alias nazwany jak prefiks, alias prowadzący poza zakres, data stream i jego indeksy `.ds-`, indeks ukryty o nazwie prefiksu, indeks cudzy, brakujący, `restricted` -> ten sam pusty wynik; pusty klaster (404 resolve); wrogie wzorce (`*`, `_all`, przecinki, kropka, `p*`, `p-*`, wykluczenia, date math, zdalny klaster) -> `invalid_argument` przed OpenSearch; prefiks krótszy niż grant nie wychodzi poza zakres; naruszenia schematu, nazwa nieznanego argumentu z podrobioną linią JSON; nieczytelna odpowiedź resolve, przekierowanie, 500 bez treści w wyniku i logu |
| `GetDocumentToolTest` | odczyt z C3, tylko pola z konfiguracji, `UntrustedText`; kodowanie `id` na drucie; każdy alias (jednego, wielu indeksów, z filtrem, z routingiem, poza zakres, do ukrytego), data stream, indeks ukryty o nazwie prefiksu i pusty klaster -> `not_found_or_denied` bez odczytu dokumentu; brak poświadczenia, 401; brakujący dokument = indeks bez uprawnień = brakujący = ukryty = indeks poza prefiksami; złe `index` i `id`; nieczytelna odpowiedź, przekierowanie, przekroczony czas, odpowiedź ponad limit |
| `GatewayOnlyAccessTest` | bez JWT 401 (`tools/call`, `ping`, `initialize`), JWT dla innego serwera, innego narzędzia, wariant serwisowy i przeterminowany -> 401; token z claimem `vs` albo `vsc` (usunięte linki) -> 401 bez dojścia do backendu; bez JWT tylko health |
| `ResourcePolicyTest` | indeks z tagiem `restricted`: `get_document` -> `not_found_or_denied`, wyszukiwanie -> pusty wynik jak dla brakującego; `access_denied` bez grupy odczytu (OpenSearch nietknięty); brak grantów -> pusty wynik albo `not_found_or_denied` bez żadnego zapytania; zapytanie ograniczone do przyznanego prefiksu; prefiks `logs-team-a` nie dochodzi do sąsiada `logs-team-ab` |
| `AggregateLogsToolTest` | liczy tylko konkretne indeksy dopuszczone polityką (ścieżka żądania z nazwami); aliasy (także o nazwie prefiksu, z filtrem, prowadzące poza zakres) i strumienie nie są liczone; prefiks poza zakresem albo `restricted` -> pusty wynik bez zapytania; wzorce i listy -> `invalid_argument`; schemat |
| `ManifestTest` | `GET /mcp-manifest` zgodny z regułami gateway'a, definicje i `definitionHash` równe `tools/list`, `resourceRefs` tylko w `get_document` i `get_index_mapping`, tytuły, zamknięte schematy; manifest z `ManifestExport` ma te same definicje co `tools/list`, `acceptedTypes` = `[token-exchange]`, brak `environment` poza `devstack` |
| `QueryScopeIndicesTest` | nazwy z odpowiedzi resolve, w której są tylko indeksy `p-2026.xx` (brak indeksu `p`); alias, strumień, indeks ukryty, sąsiad i nazwa spoza `OpenSearchIndexName` pominięte; argument tylko z listy nazw; pusty zakres bez zapytania; brak cache |
| `QueryScopeIndexScopeProviderTest` | granty `p` -> `p,p-*`, `payments-old` poza zakresem; pusty zakres; przecięcie z konfiguracją |
| `ProductionConfigurationTest`, `DevProfileLoopbackTest` | mTLS `need` poza `dev`; `https` poza `dev` (wyjątek: jawne `allow-plain-http`); adres bez userinfo, query i fragmentu; `dev` tylko na pętli zwrotnej |
| `IndexDatesTest` | granice dat w nazwach (dzień, miesiąc, margines doby, przełom roku), nazwy bez daty i z datą nieistniejącą zawsze w zestawie |
| `OpenSearchServiceTest` | prefiks wybiera `p` i `p-...` z listy nazw (nie sąsiada), polityka usuwa indeksy z zapytania i z liczników, pusta lista to pusty wynik bez wywołania, limit 100 nazw, trafienia i dokumenty spoza zakresu odrzucane, jedno rozwiązanie na trafienia jednego wyszukiwania (bez Springa) |

| `ExtendedToolsTest` | `count_logs`, `list_indices`, `get_index_mapping`, `validate_query`, `get_cluster_health`, `get_backend_info` na fixtures 2.19 i 3.x: żądania (ścieżki z nazwami, ciała), zakres, wyniki; strony i wiązanie kursora z zapytaniem, użytkownikiem i narzędziem; mapowanie aliasu, indeksu `restricted`, cudzego i brakującego = ten sam błąd bez wywołania `_mapping`; brak poświadczenia; sonda pyta anonimowo |
| `SearchLogsPagingTest` | sortowanie z drugim kluczem, `search_after` z pozycji ostatniego zwróconego trafienia, kursor bez wartości sortowania i id ukrytego dokumentu, odrzucone kursory (inne zapytanie, rozmiar, użytkownik, sfałszowany) nie dochodzą do OpenSearch, kursor nie poszerza zakresu |
| `DegradedBackendTest`, `AssumedVersionTest`, `CapabilityProbeTest`, `OpenSearchCapabilityProbeTest` | tryb `degraded` przy 401 na `GET /` (narzędzia rozszerzone odmawiają bez wywołania, podstawowe działają), oświadczenie operatora, klasyfikacja wersji (granice 2.0 i 3.x, 1.x, 4.x, Elasticsearch, odpowiedź bez wersji) |

`StubBackend` odpowiada kształtami REST API OpenSearch 2.19 i 3.x (`GET /`, `_resolve/index`, `_search`, `_doc`, `_cat/indices`, `_mapping`, `_validate/query`, `_cluster/health`; treści zależne od wersji w `opensearch-fixtures/`), łącznie z
aliasami (o nazwie przyznanego prefiksu i prowadzącymi do cudzego zespołu), data streamem z indeksami `.ds-`, indeksem
ukrytym, 404 resolve dla pustego klastra oraz nieczytelną, przekierowaną, wolną i za dużą odpowiedzią (usterki wybiera
poświadczenie, bo model nie wpływa na ścieżkę resolve); zapisuje ścieżki w postaci z drutu. Nie jest to prawdziwy klaster (STATUS.md).

Nowe narzędzie dostaje test w tym samym stylu: klasa dziedziczy po `ServerTestSupport`, odpowiedź systemu
dopisuje się w `StubBackend`.

## Dokumentacja

- `docs/jak-napisac-narzedzie.md`: zasady i kolejność kroków przy nowym narzędziu.
- `docs/architecture/overview.md`: architektura i decyzje.
- `docs/security/SECURITY.md`: niezmienniki bezpieczeństwa w tym serwerze.
- `docs/api/tools-catalog.md`: rejestr narzędzi.
- `docs/operations/deployment-vm-systemd.md`, `deploy/`, `Dockerfile`: wdrożenie.
