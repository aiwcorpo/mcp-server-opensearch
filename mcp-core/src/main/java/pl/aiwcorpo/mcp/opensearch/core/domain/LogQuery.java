package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.time.Instant;

/**
 * A validated {@code search_logs} call.
 *
 * @param text text for {@code simple_query_string} over the configured fields; never Query DSL
 * @param from start of the time range, inclusive
 * @param to   end of the time range, exclusive
 * @param size number of hits, 1-100
 */
public record LogQuery(IndexPattern pattern, String text, Instant from, Instant to, int size) {
}
