package pl.aiwcorpo.mcp.opensearch.core.domain;

import org.jspecify.annotations.Nullable;

/**
 * One row of {@code list_indices}, which carries no document counts or sizes; {@code get_index_stats} does, and says that
 * they are statistics of the index, so document level security does not apply to them.
 *
 * @param health {@code green}, {@code yellow} or {@code red}; null when OpenSearch gave none
 */
public record IndexInfo(String name, @Nullable String health) {
}
