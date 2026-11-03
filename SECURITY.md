# Security policy

## Reporting a vulnerability

Report vulnerabilities privately through GitHub: Security tab of this repository, "Report a vulnerability"
(private vulnerability reporting). Do not open a public issue for a security problem.

Include the affected version (`pom.xml`), the tool involved, the configuration (index prefixes, result fields) and steps to
reproduce. A request that gets past the internal JWT check, a way to read an index outside the caller's scope or one the policy
denies (through an alias, a data stream, a cursor or a mapping), a way to make the server send a query or an API path chosen by
the model, or a credential in a log or a tool result is in scope.

## Scope

In scope: the code of this server (`mcp-core`, `mcp-application`, `mcp-integration`, `mcp-tools`, `mcp-app`) and its deployment files
under `deploy/`.

Out of scope: the gateway, the credential broker, the policy bundles and OpenSearch itself; the `dev` profile, which relaxes TLS by
design and listens on loopback only; test fixtures under `src/test`.

## Supported versions

Only the latest released version receives fixes. Published versions are immutable; a fix ships as a new release.
