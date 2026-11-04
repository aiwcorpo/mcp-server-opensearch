# Contributing

The server follows the contracts of `mcp-contracts` and the starter `mcp-platform-starter`. A change that needs a different contract
(a credential type, a resource type, an audit event) starts as a `proposed` entry in `CONTRACT_CHANGES.md` of that repository and
needs approval before it is implemented here.

## Setup

JDK 25. Tests need no container runtime. Linux and macOS with bash 3.2 or newer; Windows only through WSL2. The platform artifacts
come from Maven Central or must be in `~/.m2` (see the README of `mcp-starter-lib`).

```bash
ci/run.sh verify     # text checks, then clean verify; the same script runs in CI
```

A new tool follows the [tool guide](https://github.com/aiwcorpo/mcp-docs/blob/main/docs/developer/tools.md) in `mcp-docs`: closed argument schema, a resource argument or a `@ResourceRef` on every result
element that names an index, no query language from the model, a test for each refusal.

## Commits

Subject in English, imperative, up to 72 characters. The body describes the change, not the process. Tests go in the same commit as
the code they cover.

## License

Contributions are licensed under Apache-2.0 (see `LICENSE`).
