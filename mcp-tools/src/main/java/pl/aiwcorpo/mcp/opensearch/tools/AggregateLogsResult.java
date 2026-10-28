package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.List;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogBucket;

/**
 * Result of {@code aggregate_logs}: the groups of one field with their document counts. Each group value is
 * {@code UntrustedText}. No total: it would count documents the caller may not read.
 */
public record AggregateLogsResult(String indexPattern, String from, String to, String groupBy, List<LogBucket> buckets) {

    static AggregateLogsResult of(String indexPattern, String from, String to, String groupBy, List<LogBucket> buckets) {
        return new AggregateLogsResult(indexPattern, from, to, groupBy, List.copyOf(buckets));
    }
}
