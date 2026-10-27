# Bezpieczeństwo serwera

Niezmienniki platformy (`mcp-platform-plan/00-overview.md`) i to, gdzie serwer je spełnia.

| Niezmiennik | W serwerze |
| --- | --- |
| Ruch tylko od gateway'a: mTLS + wewnętrzny JWT, inaczej 401 | `server.ssl.client-auth: need` z CA wewnętrznym; `InternalJwtFilter` startera (CN `mcp-gateway`, ES256 z JWKS gateway'a, `iss`, `aud`, czas życia <= 60 s, `jti` jednorazowy w Redisie, `tool` == `params.name`, `rid` == `X-Request-Id`). Bez JWT tylko `/actuator/health*`. Test: `GatewayOnlyAccessTest` |
| Token IdP nigdy do systemu | serwer go nie dostaje; do systemu idzie wyłącznie poświadczenie z C3 (`CredentialContext.require`) |
| Fail-closed | brak JWKS, Redisa, konfiguracji albo poświadczenia -> 401/503/`credential_required`/brak startu, nigdy przepuszczenie; starter odrzuca relaksacje `dev` w innych profilach |
| Sekrety nigdy w logach, audycie, wynikach i błędach | nagłówki poświadczeń ukryte po odczycie, `BackendCredential.toString()` bez wartości, redakcja komunikatów w starterze; adapter nie czyta treści błędów systemu. Test: `theCredentialNeverAppearsInTheResultOrTheLog` |
| "Nie istnieje" i "brak dostępu" -> `not_found_or_denied` | 404 i 403 OpenSearch, alias prowadzący poza prefiksy, indeks ukryty i brakujący indeks nazwany wprost dają ten sam błąd i tekst. Testy: `forbiddenMissingAndHiddenIndicesLookTheSame`, `missingForbiddenHiddenAndLeakingTargetsLookTheSame` |
| Wyszukiwanie tylko w zakresie | jeden wzorzec od prefiksu z konfiguracji (granica `-`), rozwiązywanie aliasów przed wyszukiwaniem, filtr `_index` w zapytaniu, odrzucanie trafień spoza zakresu; bez `_msearch`, `_mget`, skryptów, `?q=`, agregacji. Opis: README, "Zakres indeksów" |
| Treść systemu to dane | `data` + `dataNote`, escapowanie `<`, `>`, `&`, limity długości i rozmiaru (`UntrustedValues`) |
| Audyt z `requestId` | jedno zdarzenie C8 `tool_call` na wywołanie (logger `pl.aiwcorpo.mcp.audit`, stdout, czysta linia JSON), `requestId` z JWT |

## Zasady dla kodu

- Każdy argument narzędzia to dane od modelu: wzorzec, długość, zamknięte listy, przed wywołaniem systemu.
  Szczegóły: `docs/jak-napisac-narzedzie.md`.
- Adresy: tylko `MCP_<SYSTEM>_URL` z konfiguracji, ścieżki API z szablonu URI z argumentami jako zmiennymi
  (kodowane przez `RestClient`), bez przekierowań; żadnych adresów z argumentów (SSRF).
- Serwer nie ma konta w systemie. Poświadczenie `service-account` jest odrzucane (`credential_required`):
  zastąpiłoby DLS/FLS użytkownika w OpenSearch uprawnieniami konta technicznego.
- DLS/FLS w OpenSearch to druga warstwa za kontrolami serwera (README, "DLS/FLS w OpenSearch").
- Hinty MCP (`readOnlyHint`, `destructiveHint`) są informacją dla klienta, nie zabezpieczeniem.

## Wdrożenie

Konto bez uprawnień, sandbox systemd (`deploy/systemd/mcp-opensearch.service`), sekrety tylko w pliku
środowiska (0640, root:mcp) albo z magazynu sekretów, certyfikat serwera z SAN `mcp-opensearch.internal` z CA
wewnętrznego. Redis: użytkownik ACL `mcp-opensearch` z kluczami `jti:mcp-opensearch:*` i komendą `SET`.
