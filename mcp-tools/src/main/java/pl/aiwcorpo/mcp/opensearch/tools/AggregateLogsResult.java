package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.List;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogBucket;

/**
 * Result of {@code aggregate_logs}: the groups of one field with their document counts. Each group value is
 * {@code UntrustedText}. No total of all documents; {@code otherDocuments} counts the documents in groups that are not listed.
 */
public record AggregateLogsResult(String indexPrefix, String from, String to, String groupBy, List<LogBucket> buckets,
        long otherDocuments) {

    static AggregateLogsResult of(String indexPrefix, String from, String to, String groupBy, List<LogBucket> buckets,
            long otherDocuments) {
        return new AggregateLogsResult(indexPrefix, from, to, groupBy, List.copyOf(buckets), otherDocuments);
    }
}
