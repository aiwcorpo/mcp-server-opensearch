# mcp-opensearch: serwer MCP platformy

Serwer MCP dla systemu `opensearch`, zbudowany na `mcp-platform-starter` 3.0.0-alpha-3 (Java 25, Spring Boot
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
| `mcp-core` | domena i porty, czysta Java | `OpensearchRecord`, `RecordNotFoundException`, port wejściowy `OpensearchUseCase`, port wyjściowy `OpensearchResourcePort` |
| `mcp-application` | przypadki użycia (bez Springa) | `OpensearchService` |
| `mcp-integration` | adaptery wyjściowe do systemu | `OpensearchResourceAdapter` (REST, poświadczenie z C3, timeouty), `OpensearchBackendProperties` |
| `mcp-tools` | narzędzia MCP, powierzchnia publiczna | `OpensearchTool` (`get_record`), `OpensearchToolErrors` (wyjątki domeny -> kody C10) |
| `mcp-app` | bootstrap, wiring, jedyny jar, testy end-to-end | `McpServerApplication`, `McpToolsConfiguration` (lista narzędzi), `ApplicationWiringConfiguration` |

Zależności idą do środka: `mcp-tools` -> `mcp-application` -> `mcp-core` <- `mcp-integration`; `mcp-app` składa
całość. Resolver zasobów (`@ResourceRef`, starter beta) dojdzie w `mcp-integration` obok adaptera, bo atrybuty
zasobu (projekt, klasyfikacja) pochodzą z systemu.

## Narzędzia

| Narzędzie | Ryzyko | Argumenty | Wynik i błędy |
| --- | --- | --- | --- |
| `get_record` | `READ_ONLY` | `recordId`: 1-64 znaki `[A-Za-z0-9_-]`, zaczyna się literą lub cyfrą | `{id, project, title, status}`; `not_found_or_denied` (404 i 403 z systemu), `credential_required` (brak poświadczenia albo 401 z systemu), `invalid_argument`, `upstream_unavailable`, `internal_error` |

Nazwa widziana przez klienta to `opensearch__get_record` (prefiks dokłada gateway, C5).

## Jak przechodzi wywołanie

```
gateway --mTLS (CN mcp-gateway) + JWT C2 (aud mcp-opensearch, tool get_record) + X-MCP-Credential-->
  starter: InternalJwtFilter (inaczej 401) -> CredentialHeaderFilter -> AuditingToolCallback (C8)
  -> OpensearchTool.getRecord: walidacja recordId
  -> OpensearchService -> OpensearchResourceAdapter: CredentialContext.require("opensearch") + credential::applyTo
  -> GET {MCP_OPENSEARCH_URL}/api/records/{id}
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
| `MCP_OPENSEARCH_URL` | adres API systemu | wymagane |
| `MCP_OPENSEARCH_SSL_BUNDLE` | bundle SSL dla HTTPS systemu, np. `mcp-internal-ca`; puste = zaufanie JDK | puste |
| `MCP_REDIS_HOST`, `MCP_REDIS_PORT`, `MCP_REDIS_USER`, `MCP_REDIS_PASSWORD` | Redis dla jednorazowego `jti` (użytkownik ACL `mcp-opensearch`, klucze `jti:mcp-opensearch:*`) | `localhost`, `6379`, `mcp-opensearch`, puste |
| `MCP_PORT`, `MCP_BIND` | port i adres nasłuchu | `8443`, `0.0.0.0` |

Endpoint MCP to `/mcp` (domyślne ustawienie startera, tak jak oczekuje gateway: `internal-url:
https://mcp-opensearch.internal:8443/mcp`). Bez JWT odpowiada tylko `/actuator/health` (i `liveness`,
`readiness`), ale TLS i tak wymaga certyfikatu klienta z CA wewnętrznego.

Serwer nie wstaje bez `MCP_GATEWAY_JWKS_URI`, `MCP_CONNECT_URL`, `MCP_OPENSEARCH_URL` i plików TLS.

Na hoście ze środowiskiem `mcp-platform-dev` (sprawdzone 2026-10-27 w template). Środowisko ma certyfikaty i
użytkowników Redisa dla `mcp-jira`, `mcp-confluence`, `mcp-gitlab`, `mcp-servicedesk` i `mcp-opensearch`;
poniżej użyty jest certyfikat `mcp-jira`, serwer innego systemu bierze `certs/mcp-<system>` i ustawia
`MCP_REDIS_USER`/`MCP_REDIS_PASSWORD` (`REDIS_MCP_<SYSTEM>_PASSWORD` z `.secrets/dev.env`), inaczej wywołania z
JWT kończą się 503 (magazyn `jti` niedostępny):

```bash
dev=../mcp-platform-dev
export MCP_TLS_STORE_PASSWORD="$(grep '^TLS_STORE_PASSWORD=' $dev/.secrets/dev.env | cut -d= -f2-)"
export MCP_TLS_KEYSTORE=$dev/certs/mcp-jira/keystore.p12 MCP_TLS_TRUSTSTORE=$dev/certs/ca/truststore.p12
export MCP_PORT=18490 MCP_BIND=127.0.0.1 MCP_REDIS_PORT=16379 MCP_OPENSEARCH_URL=http://localhost:18081
export MCP_GATEWAY_JWKS_URI=https://localhost:18445/.well-known/jwks.json
export MCP_CONNECT_URL='http://localhost:18090/connections?system={system}'
java -jar mcp-app/target/mcp-opensearch.jar
curl --cacert $dev/certs/ca/ca.crt --cert $dev/certs/mcp-gateway/tls.crt --key $dev/certs/mcp-gateway/tls.key \
     --resolve mcp-jira.internal:18490:127.0.0.1 https://mcp-jira.internal:18490/actuator/health   # 200
```

`POST /mcp` bez JWT daje 401, połączenie bez certyfikatu klienta jest zrywane w handshake'u.

## Testy

| Klasa | Co sprawdza |
| --- | --- |
| `GetRecordToolTest` | poświadczenie z C3 dociera do systemu; `readOnlyHint`; brak poświadczenia i 401 systemu -> `credential_required`; 404 i 403 -> ten sam `not_found_or_denied`; zły `recordId` nie dociera do systemu; poświadczenie nie trafia do wyniku ani logu |
| `GatewayOnlyAccessTest` | bez JWT 401 (`tools/call`, `ping`, `initialize`), JWT dla innego serwera, innego narzędzia, wariant serwisowy i przeterminowany -> 401; bez JWT tylko health |
| `OpensearchServiceTest` | przypadek użycia bez Springa |

Nowe narzędzie dostaje test w tym samym stylu: klasa dziedziczy po `ServerTestSupport`, odpowiedź systemu
dopisuje się w `StubBackend`.

## Dokumentacja

- `docs/jak-napisac-narzedzie.md`: zasady i kolejność kroków przy nowym narzędziu.
- `docs/architecture/overview.md`: architektura i decyzje.
- `docs/security/SECURITY.md`: niezmienniki bezpieczeństwa w tym serwerze.
- `docs/api/tools-catalog.md`: rejestr narzędzi.
- `docs/operations/deployment-vm-systemd.md`, `deploy/`, `Dockerfile`: wdrożenie.
