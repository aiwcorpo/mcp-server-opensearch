# Contributing

The server follows the contracts of `mcp-contracts` and the starter `mcp-platform-starter`. A change that needs a different contract
(a credential type, a resource type, an audit event) starts as a `proposed` entry in `CONTRACT_CHANGES.md` of that repository and
needs approval before it is implemented here.

## Setup

JDK 25. Tests need no container runtime. Linux and macOS with bash 3.2 or newer; Windows only through WSL2. The platform artifacts
come from Maven Central or must be in `~/.m2` (see the README of `mcp-starter-lib`).

```bash
./mvnw -B verify     # the same command runs in CI
```

A new tool follows the [tool guide](https://github.com/aiwcorpo/mcp-docs/blob/main/docs/developer/tools.md) in `mcp-docs`: closed argument schema, a resource argument or a `@ResourceRef` on every result
element that names an index, no query language from the model, a test for each refusal, and a row in the tools table of the README.

## Policies

The Cedar policies of OpenSearch are in `mcp-app/src/main/resources/mcp/policies/` and go into the jar and the manifest:

| File | Content |
| --- | --- |
| `schema.cedarschema` | namespace `OpenSearch`: `OpenSearch::Index` (classification attribute `tags`), actions `read`, `update`, `delete`, `admin` |
| `resource-types.json` | the type: `idPattern` (an index name), `scopePattern` (a grantable prefix), the classification |
| `read.cedar` | reading needs `mcp-opensearch-read` and a scope that covers the index |
| `update.cedar` | rules for `update` and `delete` inside a granted prefix; no current tool uses them |

The platform generates the rest: the group a tool needs, the closed `restricted` indices (`opensearch.scope.index-tags`) and the
limits of the service account. The resolver gives an index the scope of every dash prefix of its name (`logs-team-a-2026.10` lies in
`logs`, `logs-team`, `logs-team-a` and itself), so a grant of `logs-team-a` reaches it and one of `logs-team-ab` does not; `QueryScope`
builds the index expression from the same grants. `OpenSearchPolicyDecisionsTest` states the decisions. A change to any file here
changes `policiesHash` in the manifest and needs a new approval. A change to a tool changes its `definitionHash` and needs one too.

The gateway pushes the scope mapping, the allowed AI clients and the emergency overrides to `POST /.well-known/mcp-policy-data`
after every change and every minute. A server without fresh data answers `tools/call` with `409 policy_data_stale`, and the gateway
pushes and repeats the call.

## Behaviour of the tools

- The server resolves a prefix against the indices that exist and are in the caller's scope, and sends only concrete index names
  to `_search`.
- A time range removes indices named `p-YYYY.MM.DD` or `p-YYYY.MM` whose date falls outside the range plus one day. Logs that
  arrive more than a day late into such an index can be missed by `count_logs` and `aggregate_logs`.
- `search_logs` pages with `search_after` and needs `MCP_OPENSEARCH_TIEBREAKER_FIELD`. Cursors are encrypted and bound to the user,
  the tool and the query; several instances need the same `MCP_PAGING_CURSOR_KEY`.
- The version probe (`GET /`, every 5 minutes) sets the state `supported`, `degraded` or `unavailable`. `unavailable` makes every
  tool that calls OpenSearch answer `upstream_unavailable`. A response that names another distribution stays `degraded` even with
  `MCP_OPENSEARCH_ASSUME_TESTED_VERSION=true`.
- The fixtures in `mcp-app/src/test/resources/opensearch-fixtures/` follow the documented response shapes of 2.19 and 3.x. They
  were not recorded from a live cluster.

## Tests and build

`TestGateway` from `mcp-platform-test` signs internal JWTs, and `StubBackend` imitates the OpenSearch REST API, including aliases,
data streams, hidden indices and broken responses. It is not a real cluster. The GitHub, GitLab and Jenkins files run
`./mvnw -B verify`.

The build writes `mcp-app/target/mcp-manifest.json`, the document that the running server serves at `/.well-known/mcp-manifest`.
It is kept as an artifact so that a reviewer can read what a commit publishes.

Modules: `mcp-core` (domain and ports), `mcp-application` (use cases), `mcp-integration` (OpenSearch adapter, version probe),
`mcp-tools` (MCP tools), `mcp-app` (bootstrap, the single jar, end-to-end tests).

## Running against a real cluster

`.run/` has two IntelliJ configurations. Start `LocalGateway` (serves the JWKS on port 18445 and pushes policy data to the server
with the policies of this working tree and the mapping in `.run/scope-mapping.json`), then
`McpOpenSearch (dev, behind LocalGateway)` with `MCP_OPENSEARCH_URL` and `MCP_OPENSEARCH_INDEX_PREFIXES` set. Connect an MCP client
to the gateway on `http://127.0.0.1:18500/mcp` with `Authorization: Bearer <your token>`. Edit `.run/scope-mapping.json` and the
`--groups` of the `LocalGateway` configuration for your prefixes, and restart `LocalGateway` after a change to the policies.

## Running without the platform

The `dev` profile serves plain HTTP on 127.0.0.1 and accepts JWTs signed with the published test keys of the contracts. It only
shows that the server starts and rejects requests without a JWT:

```bash
SPRING_PROFILES_ACTIVE=dev MCP_PORT=18541 "$JAVA_HOME/bin/java" -jar mcp-app/target/mcp-opensearch.jar
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18541/actuator/health    # 200
```

Never enable `dev` on a host that the gateway connects to, and never put it in an `.env` file, a systemd unit or an image.

## Release

A pushed tag `v<version>` runs `.github/workflows/release.yml`, which pushes the image to `ghcr.io/aiwcorpo/mcp-server-opensearch`
and creates the GitHub Release from the tagged commit. The workflow cannot check who pushed the tag or whether the commit was
reviewed; these repository settings are the release approval and must be in place before the first tag:

1. Settings > Environments: `release` with required reviewers, "Prevent self-review" on, and deployment branches and tags limited
   to tags `v*`.
2. Settings > Rules > Rulesets: a tag ruleset for `v*` that restricts creation, update and deletion to the release maintainers.
3. Package settings of `mcp-server-opensearch` on ghcr.io: write access only for this repository and the release maintainers.

## Commits

Subject in English, imperative, up to 72 characters. The body describes the change, not the process. Tests go in the same commit as
the code they cover.

## License

Contributions are licensed under Apache-2.0 (see `LICENSE`).
