package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.time.Instant;

/**
 * A validated {@code aggregate_logs} call.
 *
 * @param buckets number of groups to return, the biggest first
 */
public record AggregateQuery(IndexPattern pattern, LogFilter filter, Instant from, Instant to, GroupBy groupBy,
        int buckets) {
}
