package pl.aiwcorpo.mcp.opensearch.core.domain;

/**
 * An index name that passed every check of the server, as OpenSearch spells it: when the argument was an alias of
 * one index this is the index behind it, so the policy judges, and the document is read from, the real index.
 */
public record ResolvedIndex(String name) {
}
