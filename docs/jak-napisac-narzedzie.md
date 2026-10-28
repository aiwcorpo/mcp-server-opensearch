# Jak napisać narzędzie

Narzędzie to metoda `@Tool` wołana przez model przez gateway. Każdy argument jest niezaufany, a wynik trafia
do modelu, który może go potraktować jak instrukcję. Wzorcami są `search_logs` i `get_document`.

## Kroki, od środka

1. `mcp-core`: rekord domeny tylko z polami, które narzędzie ma oddać; wyjątek domeny dla "nie ma albo
   niewidoczne"; metoda portu wejściowego (`OpenSearchUseCase`) i wyjściowego (`OpenSearchPort`).
2. `mcp-application`: implementacja przypadku użycia w `OpenSearchService` (bez Springa, test JUnit obok).
3. `mcp-integration`: wywołanie systemu w adapterze. Poświadczenie zawsze z
   `CredentialContext.require(SYSTEM)` i `credential::applyTo`; dla nagłówka innego niż `Authorization`
   (np. `PRIVATE-TOKEN` GitLaba) `credential.value()`. 404 i 403 systemu -> pusty wynik (jeden kod
   `not_found_or_denied`), 401 -> `CredentialRequiredException(SYSTEM)`, 3xx i odpowiedź, której nie da się
   użyć (nie parsuje się, brak `id`, za duża) -> `BackendResponseException` ze stałym tekstem i bez przyczyny
   (komunikat parsera cytuje treść odpowiedzi). Odpowiedź do prywatnego DTO z
   `@JsonIgnoreProperties(ignoreUnknown = true)`, potem do domeny; brak wymaganych pól to błąd systemu, nie
   `IllegalArgumentException` (to byłby `invalid_argument`, czyli wina modelu).
4. `mcp-tools`: cienka metoda `@Tool` z `@ToolRisk`, walidacją argumentów i wywołaniem przypadku użycia;
   mapowanie wyjątku domeny na kod C10 w `OpenSearchToolErrors`. Każdy argument tekstowy ma
   `@ArgumentLimits(maxLength = ..., pattern = "^...$")` z tymi samymi stałymi, których używa walidacja w kodzie
   (wzorzec: `OpenSearchTools.INDEX_PATTERN_REGEX`); limity trafiają do `inputSchema`, więc widzą je gateway i
   manifest C6. `pattern` w JSON Schema nie jest kotwiczony: zawsze `^` i `$`.
5. `mcp-app`: dopisanie beana narzędzia w `McpToolsConfiguration` przez `SchemaLimitedToolCallbacks.of(...)`
   (jawna lista; sam `@Tool` nie wystarczy, a `MethodToolCallbackProvider` pominąłby `@ArgumentLimits`),
   test w `mcp-app/src/test` przez `ServerTestSupport`, odpowiedź systemu w `StubBackend`.
6. `docs/api/tools-catalog.md` i tabela narzędzi w README.

## Zasady

- Zasób zawsze jawnym argumentem. Narzędzie dostaje identyfikator projektu, rekordu, przestrzeni,
  indeksu i działa tylko na nim. Nie ma narzędzi "moje ostatnie", "wszystko z ..." ani domyślnego zasobu z
  konfiguracji: gateway i polityka (C7, `refs`) widzą tylko to, co jest w argumentach.
- Bez globalnego wyszukiwania. Wyszukiwanie zawsze w zakresie wskazanym argumentem (projekt, przestrzeń,
  grupa, prefiks indeksu), zawężonym dalej przez `QueryScope` startera (beta).
- Bez dowolnego języka zapytań. Nie przekazuj JQL, CQL, query DSL ani ścieżek API od modelu. Jeśli
  narzędzie potrzebuje filtrów, to są osobne, ograniczone pola (status, typ, przypisany) parsowane i
  składane przez serwer z escapowaniem.
- Walidacja przed systemem. Wzorzec i długość identyfikatora, limity wielkości (`size <= 100`),
  zamknięte listy wartości. Błąd walidacji to `InvalidToolArgumentException` ze stałym tekstem opisującym
  format (starter oddaje go jako `invalid_argument`): nigdy z wartością argumentu (C10: komunikat nie zawiera
  wartości argumentów) i nigdy z treścią odpowiedzi systemu. Opublikowany schemat egzekwuje starter (od alpha-6, bez zmian do alpha-10: C10
  `invalid_argument` i zdarzenie C8, bez nazw i wartości argumentów); walidacja w kodzie zostaje jako druga linia.
- Segmenty ścieżki. Szablon URI `RestClient` koduje `/`, `?`, `#`, ale wartości `.` i `..` zostają segmentami
  ścieżki (`/{index}/_doc/..`). Wzorzec identyfikatora musi je wykluczać (np. pierwszy znak alfanumeryczny);
  argumenty będące ścieżką (GitLab `get_file`) wymagają osobnej normalizacji i odrzucenia `..`.
- Błędy tylko przez kody C10. Wyjątek domeny -> `ToolErrorMapping` -> `SafeError` z kodem z
  `ToolErrorCode`. Gotowy `SanitizedToolException` wolno rzucić, gdy narzędzie samo zna kod. Inne wyjątki
  starter zamienia na `internal_error`; komunikat nie zawiera odpowiedzi systemu, nagłówków ani sekretów.
- "Nie istnieje" i "brak dostępu" to ten sam błąd `not_found_or_denied`, z tym samym tekstem.
- Ryzyko zgodne z działaniem. `READ_ONLY`, `UPDATE` (odwracalne), `DESTRUCTIVE` (nieodwracalne). Starter
  nie uruchomi serwera z narzędziem bez flagi ani z nazwą sprzeczną z flagą (`delete_*` jako `READ_ONLY`).
  Nie dodawaj parametru "czy na pewno": potwierdzenie robi gateway (C11).
- Opis deterministyczny. Co narzędzie robi i co zwraca, bez instrukcji dla modelu ("zawsze", "najpierw
  wywołaj"). Zmiana nazwy, opisu, schematu albo ryzyka zmienia `definitionHash` i wymaga ponownego
  zatwierdzenia w gateway'u (C6).
- Wynik minimalny. Tylko pola potrzebne do zadania, bez surowego HTML; tekst z systemu to dane, nie
  polecenia.
- Bez sekretów. Serwer nie ma własnego konta w systemie; nie loguj i nie zwracaj poświadczenia (starter
  usuwa je z komunikatów, ale nie licz na to w kodzie).

## Do dopisania po starterze beta

`@ResourceRef` na każdym argumencie z identyfikatorem zasobu i resolver atrybutów (projekt, klasyfikacja),
`QueryScope` w wyszukiwaniu, `ResultFilter` na wynikach listujących, manifest C6. Wzorce: `search_records`,
`update_record`, `purge_records` (M2 template'u).
