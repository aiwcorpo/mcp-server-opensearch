#!/usr/bin/env bash
# Health over mTLS: the server asks every client for a certificate of the internal CA, the probe too.
# healthcheck.sh <url> <ca.crt> <client.crt> <client.key>, e.g.
#   healthcheck.sh https://mcp-opensearch.internal:8443 /etc/mcp-opensearch/tls/ca.crt probe.crt probe.key
set -euo pipefail
URL="${1:?usage: healthcheck.sh <url> <ca.crt> <client.crt> <client.key>}"
curl -fsS --cacert "${2:?ca.crt}" --cert "${3:?client.crt}" --key "${4:?client.key}" "$URL/actuator/health" \
    | grep -q '"status":"UP"'
echo "OK"
