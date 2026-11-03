package pl.aiwcorpo.mcp.opensearch.core.domain;

import org.jspecify.annotations.Nullable;

/**
 * One row of {@code list_indices}. Document counts and sizes are left out on purpose: they are statistics of the index, so
 * document level security does not apply to them and they would count documents the caller cannot read.
 *
 * @param health {@code green}, {@code yellow} or {@code red}; null when OpenSearch gave none
 */
public record IndexInfo(String name, @Nullable String health) {
}
