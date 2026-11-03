package pl.aiwcorpo.mcp.opensearch.core.domain;

import org.jspecify.annotations.Nullable;

/**
 * One row of {@code list_indices}.
 *
 * @param health    {@code green}, {@code yellow} or {@code red}; null when OpenSearch gave none
 * @param documents live documents without nested ones, null when unknown
 * @param sizeBytes size of all copies of the index on disk, null when unknown
 */
public record IndexInfo(String name, @Nullable String health, @Nullable Long documents, @Nullable Long sizeBytes) {
}
