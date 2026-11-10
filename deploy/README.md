# Install mcp-opensearch

The OpenSearch MCP server as one container. It runs on any host that the platform can reach; the platform (gateway, broker,
portal) is installed separately.

## Requirements

- Docker Engine with Compose v2.20 or newer, 1 GiB of memory.
- The domain of the platform, from its operator. This host must reach `https://<platform>` on port 443.
- The platform must reach this host on port 8443.
- The address of the OpenSearch REST API (`https`) and the index prefixes the server may touch.
- An OpenSearch cluster with the Security plugin, set up as in "OpenSearch role" below.

## Install

1. Fill in the four empty values in `mcp-opensearch.env`:

    ```bash
    MCP_GATEWAY_JWKS_URI=https://<platform>/.well-known/mcp-jwks.json
    MCP_CONNECT_URL=https://<platform>/connections?system={system}
    MCP_OPENSEARCH_URL=https://<opensearch>:9200
    MCP_OPENSEARCH_INDEX_PREFIXES=logs-team-a,logs-team-b
    ```

2. If the cluster is OpenSearch 2.x or 3.x with the Security plugin, set this line so that the server runs with all tools:

    ```bash
    MCP_OPENSEARCH_ASSUME_TESTED_VERSION=true
    ```

3. If the platform certificate is signed by a company CA, add that CA to a trust store in `certs/`:

    ```bash
    keytool -importcert -noprompt -alias company-ca -file company-ca.crt \
      -keystore certs/truststore.p12 -storetype PKCS12 -storepass changeit
    chmod 644 certs/truststore.p12
    ```

    Then uncomment the `JAVA_TOOL_OPTIONS` line in `mcp-opensearch.env`. When OpenSearch uses a CA that the JVM does not know,
    import that CA into the same file.

4. Start the server:

    ```bash
    docker compose up -d
    ```

5. Read the certificate fingerprint:

    ```bash
    docker compose logs mcp-opensearch | grep event=tls_certificate
    ```

    Expected: `event=tls_certificate source=generated fingerprint=sha256:...`

6. Send the platform operator the address `https://<this host>:8443/mcp` and the fingerprint.

## OpenSearch role

The server sends each user's own token to OpenSearch, so the Security plugin must accept that token (JWT or OpenID Connect) and map
the user's backend roles to OpenSearch roles. A role needs:

| Permission | For |
| --- | --- |
| `indices:admin/resolve/index` | all tools |
| `indices:data/read/search*` | `search_logs`, `count_logs`, `aggregate_logs`, `find_log_patterns` |
| `indices:data/read/get` | `get_document` |
| `indices:admin/mappings/get` | `get_index_mapping` |
| `indices:admin/validate/query` | `validate_query` |
| `indices:monitor/stats` | `get_index_stats` |
| monitor actions (for example `cluster_monitor`) | `list_indices`, `get_cluster_health` |

Action names differ between plugin releases, so confirm them on the first run. Set `do_not_fail_on_forbidden: true` in the role:
a list that includes one forbidden index then returns the rest.

## Check that it works

```bash
curl -fsSk https://localhost:8443/actuator/health
```

Expected: `{"status":"UP"...}`. Every other path answers 401 without a token from the gateway.

## Add the server to the platform

The platform operator does this in the portal, Administration, with a fresh login and OTP:

1. Systems: add `opensearch`, adapter `opensearch`, address `https://<opensearch>:9200`, credential type `token-exchange`, and the
   audience that the identity provider puts in the token for OpenSearch. Users do not paste a token: the broker exchanges their login.
2. Servers, Add server: `https://<this host>:8443/mcp`.
3. Approvals: compare the fingerprint with the one from step 5 and approve the connection, then the policies, then the tools.
4. In Keycloak, create the groups `mcp-opensearch-read` and `mcp-opensearch-ops` and add the users. Only users who need
   `get_index_stats`, `get_cluster_health` and `get_backend_info` get `mcp-opensearch-ops`.
5. Scope mapping: a group such as `mcp-opensearch-idx-logs-team-a` -> `OpenSearch::Index/logs-team-a`. The user then reads
   `logs-team-a` and `logs-team-a-*`. Indices tagged `restricted` in `opensearch.scope.index-tags` are closed to everyone.

## Common problems

| Symptom | Cause | Fix |
| --- | --- | --- |
| Server stops at start: cannot fetch the key set | The host does not resolve `<platform>` or does not trust its certificate | Check DNS; do step 3 |
| Portal, Add server: `tls_handshake_failed` or `manifest_unreachable` | Port 8443 closed between the platform and this host | Open it to the platform host |
| Tool call: `upstream_unavailable`, log has `SSLHandshakeException` | The trust store is missing the OpenSearch CA, or `JAVA_TOOL_OPTIONS` is commented out | Import the CA, then `docker compose up -d --force-recreate` |
| `list_indices`, `validate_query` or `get_cluster_health` refuse; state `degraded` | The cluster answers the version probe with 401 | Set `MCP_OPENSEARCH_ASSUME_TESTED_VERSION=true` (step 2) |
| `search_logs` returns the first page only | `MCP_OPENSEARCH_TIEBREAKER_FIELD` is empty | Set a field with a unique value in every document |
| Searches return nothing, the indices exist | The logs are in a data stream or behind an alias | Not supported; point the prefix at the backing indices |
| Server status `certificate_changed` | The state volume was recreated and the server generated a new certificate | Approve the new fingerprint in the portal |

The state volume keeps the certificate between restarts. `docker compose down -v` deletes it.

## Your own certificate

Put a PEM pair or a PKCS12 keystore in `certs/` and set `MCP_TLS_CERT` and `MCP_TLS_KEY`, or `MCP_TLS_KEYSTORE` and
`MCP_TLS_STORE_PASSWORD`, in `mcp-opensearch.env`. A certificate from a CA the gateway trusts, for the host name in the address, is
trusted without pinning, and a renewal needs no new approval.

## Several instances

Instances behind one address need the same values in `mcp-opensearch.env`:

| Setting | Why |
| --- | --- |
| `MCP_REDIS_HOST` and the other `MCP_REDIS_*` | one-time token ids shared between instances |
| `MCP_PAGING_CURSOR_KEY` (`openssl rand -base64 32`) | a cursor from one instance works on another |
| `MCP_TLS_CERT` and `MCP_TLS_KEY` | the gateway sees one fingerprint |

## Closed network

Copy the image to your registry and set it in a `.env` file next to `compose.yaml`:

```bash
MCP_REGISTRY=artifactory.corp.test/mcp
```

## Upgrade from 1.x

Remove `MCP_GATEWAY_POLICY_DATA_URL`, `MCP_GATEWAY_TLS_NAME`, `MCP_INTERNAL_URL`, `MCP_TLS_TRUSTSTORE` and the Redis service of the
old compose file, set `MCP_GATEWAY_JWKS_URI`, and start. The operator approves the connection and the policies again; the server
refuses to start while an old setting is left. `get_cluster_health` and `get_backend_info` now need `mcp-opensearch-ops` in
addition to `mcp-opensearch-read`.

## Virtual machine without Docker

`systemd/` has a unit and its environment file for the jar (`mcp-app/target/mcp-opensearch.jar` after `./mvnw verify`):

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin mcp
sudo install -d -o root -g mcp -m 0750 /opt/mcp-opensearch /etc/mcp-opensearch
sudo install -o root -g mcp -m 0640 mcp-opensearch.jar /opt/mcp-opensearch/mcp-opensearch.jar
sudo install -o root -g mcp -m 0640 systemd/mcp-opensearch.env.template /etc/mcp-opensearch/mcp-opensearch.env
sudo install -m 0644 systemd/mcp-opensearch.service /etc/systemd/system/
```

Fill in `/etc/mcp-opensearch/mcp-opensearch.env` as in steps 1 and 2, then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now mcp-opensearch
journalctl -u mcp-opensearch | grep event=tls_certificate
```
