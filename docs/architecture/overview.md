# Architektura

## Miejsce w platformie

```
klient AI --OIDC--> MCP gateway --mTLS + JWT C2 + poświadczenie C3--> mcp-opensearch --API--> system opensearch
                       |  ^
                       |  +-- credential broker (poświadczenie użytkownika)
                       +-- polityki (Cedar), potwierdzenia, audyt
```

Gateway uwierzytelnia użytkownika, decyduje o dostępie do narzędzia, pobiera z brokera poświadczenie
użytkownika do systemu i wysyła do serwera nowy wewnętrzny JWT na każde żądanie HTTP. Serwer sprawdza, że
żądanie przyszło od gateway'a (certyfikat klienta CN `mcp-gateway`, JWT z `aud` = `mcp-opensearch`, `tool` ==
nazwa wywoływanego narzędzia, jednorazowy `jti`), i woła system jako ten użytkownik. Serwer nie zna tokenu
IdP użytkownika i nie przechowuje żadnego sekretu.

## Heksagon w modułach

```
            mcp-tools (@Tool)                      mcp-integration (REST do systemu)
                  |                                         |
                  v                                         v implementuje
           mcp-application (przypadki użycia) ---> mcp-core (domena, porty in/out)
                              mcp-app: bootstrap, wiring, jar, testy end-to-end
             wspólne: mcp-platform-starter (JWT C2, C3, audyt C8, błędy C10, transport MCP)
```

`mcp-core` nie zależy od Springa ani MCP, więc reguły domeny testuje się bez transportu. Kod przekrojowy jest
w starterze; serwer go nie powiela.

## Transport

Streamable HTTP (Spring AI 2.0, MCP SDK 2.0, tryb SYNC), endpoint `/mcp`, wersje protokołu 2025-03-26 ..
2025-11-25, sesje `Mcp-Session-Id`. Gateway trzyma osobną sesję na użytkownika (`sub`), więc serwer nie wiąże
sesji z użytkownikiem sam. `CallerContext` i `CredentialContext` są per wątek żądania, dlatego starter odrzuca
tryb ASYNC.

## Decyzje

- mTLS kończy sam serwer (`server.ssl.client-auth: need`), bez nginx przed nim: certyfikat klienta musi dotrzeć
  do aplikacji, a gateway sprawdza SAN `mcp-opensearch.internal` serwera.
- Konfiguracja bazowa jest produkcyjna; profil `dev` istnieje dla testów i wyłącza tylko TLS.
- Jeden system na serwer: `server-id` = `mcp-<system>`, `CredentialContext.require` innego systemu to błąd
  programisty.
- Adapter nie podąża za przekierowaniami i nie czyta treści błędów systemu.
- Ponawianie i circuit breaker w adapterze: M2 template'u (teraz tylko timeouty).
- Tylko odczyt, bez narzędzia "dowolne API": model podaje wartości, zapytanie, nazwy indeksów i ciało buduje serwer.
- Wersje: `OpenSearchCapabilityProbe` czyta `GET /` anonimowo (serwer nie ma sekretu); 2.x i 3.x to `supported`, reszta `degraded`, w którym
  narzędzia wymagające sprawdzonych API (`list_indices`, `validate_query`, `get_cluster_health`) odmawiają. Wywołania są wspólne dla 2.x i 3.x,
  więc nie ma adapterów per wersja.
- Stronicowanie `search_logs` przez `search_after` z polem o unikalnej wartości (nie `_id`) i kursorem `CursorCodec`; wyłączone, dopóki pole nie jest ustawione.
