package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.List;

/**
 * The groups of {@code aggregate_logs}.
 *
 * @param otherDocuments documents in groups that are not listed (the {@code terms} aggregation keeps the biggest ones), so a
 *                       caller can tell that the listed counts are not the whole
 */
public record LogBuckets(List<LogBucket> buckets, long otherDocuments) {

    public LogBuckets {
        buckets = List.copyOf(buckets);
    }

    public static LogBuckets empty() {
        return new LogBuckets(List.of(), 0);
    }
}
