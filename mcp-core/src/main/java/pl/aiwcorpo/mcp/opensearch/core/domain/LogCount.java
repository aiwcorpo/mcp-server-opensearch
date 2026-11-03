package pl.aiwcorpo.mcp.opensearch.core.domain;

/**
 * Number of documents matching a {@code count_logs} call.
 *
 * @param exact false when OpenSearch gave a lower bound or the count hit the time limit of the request
 */
public record LogCount(long count, boolean exact) {
}
