package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.time.Instant;

/**
 * A validated {@code aggregate_logs} call.
 *
 * @param prefix  an index prefix {@code p}, as in {@link LogQuery}
 * @param buckets number of groups to return, the biggest first
 */
public record AggregateQuery(String prefix, LogFilter filter, Instant from, Instant to, GroupBy groupBy,
        int buckets) {
}
