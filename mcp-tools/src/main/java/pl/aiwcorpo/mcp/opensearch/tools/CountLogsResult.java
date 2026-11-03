package pl.aiwcorpo.mcp.opensearch.tools;

/** Result of {@code count_logs}; {@code exact} is false when OpenSearch gave a lower bound or the request timed out. */
public record CountLogsResult(String indexPrefix, String from, String to, long count, boolean exact) {
}
