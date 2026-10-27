# Wdrożenie na VM (systemd)

Serwer działa jako usługa systemd, bez kontenera, i sam kończy mTLS na porcie 8443. Gateway łączy się z nim
pod nazwą `mcp-opensearch.internal` (SAN certyfikatu serwera).

## Wymagania

- JRE 25 w `/usr/bin/java`, jar `mcp-app/target/mcp-opensearch.jar` (`./mvnw verify`).
- Certyfikat serwera (SAN `mcp-opensearch.internal`, CA wewnętrzne) i truststore z CA wewnętrznym, oba PKCS12.
- Redis z użytkownikiem ACL `mcp-opensearch` (klucze `jti:mcp-opensearch:*`, komenda `SET`).
- Dostęp sieciowy: od gateway'a do 8443, z serwera do JWKS gateway'a, Redisa i API systemu.

## Układ na hoście

```
/opt/mcp-opensearch/mcp-opensearch.jar         0640 root:mcp
/etc/mcp-opensearch/mcp-opensearch.env         0640 root:mcp  (z deploy/systemd/mcp-opensearch.env.template)
/etc/mcp-opensearch/tls/keystore.p12        0640 root:mcp
/etc/mcp-opensearch/tls/truststore.p12      0640 root:mcp
```

Logi (aplikacja i audyt C8) idą na stdout, czyli do journald; audyt to linie JSON z loggera
`pl.aiwcorpo.mcp.audit` do przekazania do SIEM.

## Kroki

1. `deploy/scripts/install.sh mcp-app/target/mcp-opensearch.jar` (konto `mcp`, katalogi, unit).
2. Plik środowiska z szablonu, keystore i truststore do `/etc/mcp-opensearch/tls`.
3. `systemctl enable --now mcp-opensearch`, potem `journalctl -u mcp-opensearch -f`.
4. `deploy/scripts/healthcheck.sh https://mcp-opensearch.internal:8443 <ca.crt> <cert klienta> <klucz>`; serwer
   wymaga certyfikatu klienta także dla health.

Aktualizacja: `deploy/scripts/update.sh <nowy.jar>` (poprzedni jar zostaje jako `.bak.<data>`). Wycofanie:
przywrócić kopię i `systemctl restart mcp-opensearch`.

## Typowe problemy

- Brak startu z `Invalid MCP platform configuration`: brakuje `MCP_GATEWAY_JWKS_URI` albo `MCP_CONNECT_URL`.
- 503 na każde wywołanie: Redis niedostępny albo użytkownik ACL bez prawa do `jti:mcp-opensearch:*`, albo JWKS
  gateway'a niedostępny dłużej niż `jwks-max-stale`.
- 401 mimo działającego gateway'a: inny `iss`/`aud`, zegar przesunięty o ponad 30 s, gateway bez certyfikatu
  CN `mcp-gateway`; powód jest w logu (`event=internal_jwt_rejected reason=...`).
