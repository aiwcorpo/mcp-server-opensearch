package pl.aiwcorpo.mcp.opensearch.core.domain;

import org.jspecify.annotations.Nullable;

/**
 * One row of {@code get_index_stats}: the fixed metric set of {@code GET <index>/_stats}. Document counts and sizes come
 * from OpenSearch as they are, so document level security does not apply to them.
 *
 * @param docsCount        documents in the primary shards, including documents not yet merged away; null when OpenSearch gave none
 * @param storeSizeBytes   disk size of all shards (primaries and replicas); null when OpenSearch gave none
 * @param indexingTotal    indexing operations since the shards started, primaries
 * @param searchQueryTotal query operations since the shards started, all shards
 */
public record IndexStats(String name, @Nullable Long docsCount, @Nullable Long storeSizeBytes,
        @Nullable Long indexingTotal, @Nullable Long searchQueryTotal) {
}
