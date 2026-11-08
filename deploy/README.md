# Install mcp-opensearch

The OpenSearch server as one container (Docker Compose) or one systemd service. It runs behind the MCP gateway; the gateway,
the credential broker and the identity provider are installed elsewhere. Step by step for a VM: the
[VM installation guide](https://github.com/aiwcorpo/mcp-docs/blob/main/docs/operator/install-vm.md) in `mcp-docs`. The image is
built from the `Dockerfile` in the repository root.

You start the server; the platform operator enters its address in the portal and approves the connection. Nobody issues a certificate
for the server, creates a token or registers anything from a pipeline: the gateway connects to the address, reads the certificate,
pulls the manifest and pushes the policy data itself. The host of the server may be another machine than the platform.

```
deploy/
|-- compose.yaml                      the server as a container (read-only, no capabilities)
|-- mcp-opensearch.env                settings for Compose: fill in the empty values
|-- certs/                            keystore.p12 and truststore.p12 go here (never committed)
`-- systemd/
    |-- mcp-opensearch.service        unit (account mcp, systemd sandbox, logs to journald)
    `-- mcp-opensearch.env.template   settings -> /etc/mcp-opensearch/mcp-opensearch.env
```

## Prerequisites

- Docker Engine with Compose v2.20 or newer (or Java 25 and systemd), 1 GiB of memory.
- The public key set of the gateway, `https://<domain of the platform>/.well-known/mcp-jwks.json` (the operator tells you the domain). The
  host must reach that domain on port 443, and the platform must reach the port of the server (8443 by default).
- The addresses of the OpenSearch REST API (`https`) and of the portal page for connecting an account, and the index prefixes this server may touch (`MCP_OPENSEARCH_INDEX_PREFIXES`).
- A certificate is optional. Without one the server generates a self-signed certificate on its first start and keeps it in the state
  volume. With your own (PEM files or a PKCS12 keystore, from any CA, signed or not) put it in `certs/`; see `mcp-opensearch.env`.

## Install

1. Fill in the empty values in `mcp-opensearch.env`: `MCP_GATEWAY_JWKS_URI`, `MCP_CONNECT_URL`, the address of the system and
   `MCP_OPENSEARCH_INDEX_PREFIXES`. The other lines are optional and commented out.
2. Get the image: `docker compose pull`, or after `./mvnw verify` in the repository `docker compose build`.
3. Start:

    ```bash
    docker compose up -d
    docker compose logs mcp-opensearch | grep event=tls_certificate
    ```

    The server does not start with a missing setting and says which. The log line has the fingerprint of the certificate it serves:

    ```
    event=tls_certificate source=generated fingerprint=sha256:9f2c...e1 detail="..."
    ```

    Keep it: the operator compares it with the one the portal shows before approving the connection. `source` is `generated`, `pem` or `keystore`.
    The certificate is generated once and reused at every start, as long as the state volume (`state`, mounted at `/var/lib/mcp/state`) stays;
    a new volume is a new certificate and a new fingerprint to approve. With `docker compose down -v` the volume is gone.

4. Give the operator the address: `https://<host>:8443/mcp`, with the host name or address the platform reaches, and the fingerprint.

Until the gateway has pushed policy data (a fresh start, or a restart), the server answers `tools/call` with `409 policy_data_stale`, which the
gateway handles by pushing and repeating the call. The gateway pushes after every change and every minute, so the state lasts seconds. Until an
administrator has approved the policies it refuses every call (`upstream_unavailable`, reason `policies_not_approved` in the audit), which is the state
the next section ends.

## The platform certificate comes from a company CA

`MCP_GATEWAY_JWKS_URI` is an `https` address. When the certificate of the platform is signed by a CA that the JVM does not know (a company
CA), the server cannot read the key set and does not start. Put that CA in a truststore, keep it in `certs/` (the directory is mounted
read-only at `/certs`) and point the JVM at it with `JAVA_TOOL_OPTIONS` in `mcp-opensearch.env`:

```bash
keytool -importcert -noprompt -alias company-ca -file company-ca.pem \
  -keystore certs/truststore.p12 -storetype PKCS12 -storepass changeit
```

```
JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/certs/truststore.p12 -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit
```

The password only guards the integrity of the file and is not a secret, but the JVM prints `JAVA_TOOL_OPTIONS` to its log at start. The JVM
reads no certificate from a store without the password. `javax.net.ssl.trustStore` replaces the default truststore of the JVM, so the CAs of
public sites stop being trusted: import every CA the server needs (for example the one of the backend system), or copy
`$JAVA_HOME/lib/security/cacerts`, add the company CA to the copy and point the option at the copy. On a virtual machine the same line goes
into the environment file of the unit, with the path of the file on the host.

## Add the server to the platform

Systems and servers are data of the broker and the gateway, not settings of this directory. Nothing restarts, in either direction:
every instance sees a change within a few seconds. Adding, changing and removing need an administrator token with a fresh MFA login
(`mcp-platform-admin`); the portal (Administration) does all of this, and the calls below are the same operations for scripts.

1. **System in the broker.** OpenSearch gets the user's own token, exchanged at the identity provider, so the system has the type
   `token-exchange` only. `exchangeAudience` is the audience the identity provider puts in the token for OpenSearch:

    ```bash
    curl --cert bff.crt --key bff.key --cacert ca.crt -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
      -H 'If-None-Match: *' -X PUT https://mcp-credential-broker.corp.internal:8443/v1/admin/systems/opensearch -d '{
        "baseUrl":"https://opensearch.corp.internal:9200","adapter":"opensearch","exchangeAudience":"opensearch",
        "supportedTypes":["token-exchange"],"defaultType":"token-exchange","secretScheme":"Bearer"}'
    ```

2. **Server in the gateway.** The operator enters the address `https://<host>:8443/mcp` in the portal (Administration, Servers, Add), or calls
   `POST /admin/v1/servers` with `{"internalUrl": "..."}` (fresh MFA). The gateway connects, reads the certificate, pulls the manifest and shows
   a card with the system, the certificate (subject, issuer, names, validity, fingerprint) and the tools and policies it found. The `serverId` is
   `mcp-opensearch`, derived from the manifest. An address that is loopback or link-local is refused, and `http` needs `allowPlainHttp`, which only an
   administrator may set (for a server inside the Docker network of the platform, started with `MCP_PLAIN_HTTP=true`).
3. **Approve the connection** (fresh MFA), after comparing the fingerprint on the card with the one in the server log:
    - a certificate that a CA trusted by the gateway signed, for the name of the address, is trusted as `ca`, and a renewal needs nothing;
    - any other certificate, a self-signed one included, is pinned (`pinned`) by its fingerprint: when the server later presents another
      certificate it becomes unreachable, and the portal shows the new fingerprint for approval.
4. **Approve** the policies (`GET /admin/v1/registry/servers/mcp-opensearch/policies/pending` shows them, `POST .../policies/approve` takes
   their `policiesHash`) in the portal under Administration, Servers. A later release that changes a policy or a declaration
   sends the policies of the server back to approval (`policiesStatus` is `pending_approval`; the connection stays `connected`) until they are approved again, and a changed tool waits on its own; the server refuses calls with `policies_differ`
   in the meantime.
5. **Groups.** The server needs the group `mcp-opensearch-read` in the identity provider (the gateway derives it from the risk of
   a tool; `mcp-opensearch-write` and `-destructive` only matter when a tool that changes data is added) and `mcp-opensearch-ops`,
   which `get_cluster_health` and `get_backend_info` require in addition to the read group. Reading is limited to the
   index prefixes that a group is mapped to; map them in Administration, Scope mapping, or with `PUT /admin/v1/policy/scope-mapping`
   (the whole document, `If-Match` with the `ETag` of the last `GET`):

    ```json
    {"scopeMapping": [{"group": "mcp-opensearch-idx-logs-team-a", "scopes": ["OpenSearch::Index/logs-team-a"]}]}
    ```

    A user in `mcp-opensearch-read` and `mcp-opensearch-idx-logs-team-a` may then read `logs-team-a` and `logs-team-a-*`, not
    `logs-team-ab`. A scope of a prefix the server is not configured for (`MCP_OPENSEARCH_INDEX_PREFIXES`) grants nothing. Indices
    tagged `restricted` in `opensearch.scope.index-tags` are closed to everyone, whatever the groups.

### Remove the server

Delete the server in the gateway (`DELETE /admin/v1/servers/mcp-opensearch`) and the system in the broker
(`DELETE /v1/admin/systems/opensearch`). Its tools leave every `tools/list`, calls are refused as for an unknown tool, and the entries
of the scope mapping for `OpenSearch::Index` go with it. Then stop the container or the service.

## Policy data

The gateway pushes the scope mapping, the allowed AI clients (`claude-code`, `claude-ai` by default) and the emergency overrides to
`POST /.well-known/mcp-policy-data` of the server after every change and every minute; the server fetches nothing and holds the data in
memory. A server that was restarted, or missed a push, answers `tools/call` with `409 policy_data_stale` and the gateway pushes and repeats the
call once. The policies themselves are in the jar (`mcp/policies/`); there is nothing to configure for them and no key to distribute.

## Several instances

Behind a balancer the instances share one address, so they need what one instance keeps in its own memory or state directory: Redis for the
one-time `jti` (`MCP_REDIS_HOST` and the lines under it in `mcp-opensearch.env`; without it a token could be used once on each instance), the same
`MCP_PAGING_CURSOR_KEY` (otherwise a cursor from one instance is refused by another) and the same certificate (`MCP_TLS_CERT` and `MCP_TLS_KEY`,
otherwise each instance generates its own and the gateway sees the fingerprint change from one call to the next). Compose runs one instance; Redis is
yours.

## Closed network

Copy the image to your registry and point Compose at it in a `.env` file next to `compose.yaml`:

```bash
MCP_REGISTRY=artifactory.corp.test/mcp
```

## Upgrade from 1.x

Version 2.0.0 gets the policy data pushed by the gateway and the policies from the jar, and refuses to start while a setting of the earlier
policy bundles, `policy-data.*` or `mtls.*` is left. Remove `MCP_GATEWAY_POLICY_DATA_URL`, `MCP_GATEWAY_TLS_NAME`, `MCP_INTERNAL_URL`,
`MCP_TLS_TRUSTSTORE`, the Redis service of the earlier compose file and its `MCP_REDIS_*` lines (one instance needs none), and set
`MCP_GATEWAY_JWKS_URI` to the public key set of the gateway. The certificate from the internal CA is not needed any more: keep it
(`MCP_TLS_CERT`, `MCP_TLS_KEY` or `MCP_TLS_KEYSTORE` with `MCP_TLS_STORE_PASSWORD`) or let the server generate one, and have the operator approve
the connection. The registration of the manifest from the pipeline is gone: the gateway pulls it. Approve the policies as above before the first call.
`get_cluster_health` and `get_backend_info` still need `mcp-opensearch-ops`, now in addition to `mcp-opensearch-read` (the
group is the `additionalGroup` of the tool and part of its `definitionHash`, so the gateway asks for approval again).

## Virtual machine without Docker

`systemd/` has a unit and its environment file for the jar (`mcp-app/target/mcp-opensearch.jar` after `./mvnw verify`). The variables are
the ones of `mcp-opensearch.env`; the unit's `StateDirectory=` keeps the certificate in `/var/lib/mcp-opensearch`.

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin mcp
sudo install -d -o root -g mcp -m 0750 /opt/mcp-opensearch /etc/mcp-opensearch
sudo install -o root -g mcp -m 0640 mcp-opensearch.jar /opt/mcp-opensearch/mcp-opensearch.jar
sudo install -o root -g mcp -m 0640 systemd/mcp-opensearch.env.template /etc/mcp-opensearch/mcp-opensearch.env   # then fill it in
sudo install -m 0644 systemd/mcp-opensearch.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now mcp-opensearch
journalctl -u mcp-opensearch | grep event=tls_certificate
```

Health (any certificate; no token is needed for this path):

```bash
curl -fsSk https://localhost:8443/actuator/health
```

Update: keep the old jar for a rollback, replace it and restart.

```bash
sudo cp -a /opt/mcp-opensearch/mcp-opensearch.jar /opt/mcp-opensearch/mcp-opensearch.jar.bak
sudo install -o root -g mcp -m 0640 new-mcp-opensearch.jar /opt/mcp-opensearch/mcp-opensearch.jar
sudo systemctl restart mcp-opensearch
```
