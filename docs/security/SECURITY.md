# Bezpieczeństwo serwera

Niezmienniki platformy (`mcp-platform-plan/00-overview.md`) i to, gdzie serwer je spełnia.

| Niezmiennik | W serwerze |
| --- | --- |
| Ruch tylko od gateway'a: mTLS + wewnętrzny JWT, inaczej 401 | `server.ssl.client-auth: need` z CA wewnętrznym; `InternalJwtFilter` startera (CN `mcp-gateway`, ES256 z JWKS gateway'a, `iss`, `aud`, czas życia <= 60 s, `jti` jednorazowy w Redisie, `tool` == `params.name`, `rid` == `X-Request-Id`). Bez JWT tylko `/actuator/health*`. Test: `GatewayOnlyAccessTest` |
| Token IdP nigdy do systemu | serwer go nie dostaje; do systemu idzie wyłącznie poświadczenie z C3 (`CredentialContext.require`) |
| Fail-closed | brak JWKS, Redisa, konfiguracji albo poświadczenia -> 401/503/`credential_required`/brak startu, nigdy przepuszczenie; starter odrzuca relaksacje `dev` w innych profilach |
| Sekrety nigdy w logach, audycie, wynikach i błędach | nagłówki poświadczeń ukryte po odczycie, `BackendCredential.toString()` bez wartości, redakcja komunikatów w starterze; adapter zamienia błędy i nieczytelne odpowiedzi systemu na wyjątki bez treści; backend tylko po `https`. Testy: `theCredentialNeverAppearsInTheResultOrTheLog`, `anUnreadableBodyStaysOutOfTheResultAndTheLog`, `outsideDevTheBackendMustBeHttps` |
| "Nie istnieje" i "brak dostępu" nie do odróżnienia | `get_document`: 404 i 403 OpenSearch, alias, data stream, indeks ukryty, brakujący i poza prefiksami dają ten sam `not_found_or_denied` ze stałym tekstem platformy; `search_logs` i `aggregate_logs` dają ten sam pusty wynik. Testy: `anIndexFromAnotherTeamAndAMissingOneGiveTheSameEmptyResult`, `missingForbiddenHiddenAndLeakingTargetsLookTheSame`, `anAliasIsNotFoundOrDeniedWhateverItPointsAt` |
| Wyszukiwanie tylko w zakresie (CC-160) | żaden wzorzec z argumentu ani `p-*` nie idzie do wyszukiwania, `_count` ani agregacji: `GET _resolve/index` z wyrażeniem zakresu (bez cache), `scope.opensearchSearchTarget` (bez aliasów i strumieni), prefiks z argumentu wybiera nazwy, żądanie nazywa je jedna po drugiej; filtr `_index` w zapytaniu, odrzucanie trafień spoza zakresu; bez `_msearch`, `_mget`, skryptów, `?q=`. Opis: README, "Zakres indeksów" |
| Treść systemu to dane | każda wartość i nazwa z dokumentu w `data` to `UntrustedText` (`kind: untrusted-data`, CC-072) po `TextHygiene.normalize` i cięciu, bez escapowania (klient nie renderuje HTML), limity długości, liczby wpisów i rozmiaru wyniku (`UntrustedValues`, `SearchLogsResult`); `id` i `timestamp` tylko w kształcie przyjmowanym przez narzędzia |
| Nazwa argumentu od klienta nie trafia do logu | walidację argumentów robi starter (alpha-6) bez echa nazw i wartości. Test: `anUnknownArgumentNameCannotForgeALogLine` |
| Audyt z `requestId` | jedno zdarzenie C8 `tool_call` na wywołanie (logger `pl.aiwcorpo.mcp.audit`, stdout, czysta linia JSON), `requestId` z JWT |

## Zasady dla kodu

- Każdy argument narzędzia to dane od modelu: wzorzec, długość, zamknięte listy, przed wywołaniem systemu.
  Szczegóły: `docs/jak-napisac-narzedzie.md`.
- Adresy: tylko `MCP_OPENSEARCH_URL` z konfiguracji (poza `dev` wyłącznie `https`, bez userinfo, query i
  fragmentu; `http` tylko jawnym `MCP_OPENSEARCH_ALLOW_PLAIN_HTTP` dla mocków środowiska dev), ścieżki API z
  szablonu URI z argumentami jako zmiennymi (kodowane przez `RestClient`; test sprawdza postać na drucie), bez
  przekierowań; żadnych adresów z argumentów (SSRF).
- Odpowiedź systemu: najwyżej `max-response-size` (1 MB), tylko pola domeny, nigdy w komunikacie ani logu
  (także odpowiedź 2xx, której nie da się sparsować).
- Profil `dev` (klucze testowe, bez mTLS) jest w jarze, ale wstaje tylko na adresie pętli zwrotnej (starter
  3.0.0-alpha-5, test `DevProfileLoopbackTest`). Serwer, do którego łączy się gateway, działa wyłącznie w
  konfiguracji bazowej.
- Serwer nie ma konta w systemie. Poświadczenie `service-account` jest odrzucane (`credential_required`):
  zastąpiłoby DLS/FLS użytkownika w OpenSearch uprawnieniami konta technicznego.
- DLS/FLS w OpenSearch to druga warstwa za kontrolami serwera (README, "DLS/FLS w OpenSearch").
- Hinty MCP (`readOnlyHint`, `destructiveHint`) są informacją dla klienta, nie zabezpieczeniem.

## Wdrożenie

Konto bez uprawnień, sandbox systemd (`deploy/systemd/mcp-opensearch.service`), sekrety tylko w pliku
środowiska (0640, root:mcp) albo z magazynu sekretów, certyfikat serwera z SAN `mcp-opensearch.internal` z CA
wewnętrznego. Redis: użytkownik ACL `mcp-opensearch` z kluczami `jti:mcp-opensearch:*` i komendą `SET`.
