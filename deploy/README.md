# deploy/

Uruchomienie serwera na VM przez systemd. Opis krok po kroku: `docs/operations/deployment-vm-systemd.md`.
Obraz kontenera: `Dockerfile` w katalogu głównym.

```
deploy/
├── systemd/
│   ├── mcp-opensearch.service         unit (konto mcp, sandbox systemd, logi do journald)
│   └── mcp-opensearch.env.template    zmienne środowiska -> /etc/mcp-opensearch/mcp-opensearch.env
└── scripts/
    ├── install.sh                  pierwsza instalacja (konto, katalogi, unit)
    ├── update.sh                   podmiana jara z kopią do wycofania
    └── healthcheck.sh              /actuator/health po mTLS
```
