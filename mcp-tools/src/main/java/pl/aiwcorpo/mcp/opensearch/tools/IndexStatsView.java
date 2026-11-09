package pl.aiwcorpo.mcp.opensearch.tools;

import org.jspecify.annotations.Nullable;
import pl.aiwcorpo.mcp.platform.resource.ResourceRef;

/**
 * One row of {@code get_index_stats}. The name is a {@code @ResourceRef}, so the starter drops the rows of indices the
 * policy refuses even if the server let one through. The numbers are index statistics and ignore document level security.
 */
public record IndexStatsView(@ResourceRef(type = OpenSearchIndexResolver.TYPE) String name,
        @Nullable Long docsCount, @Nullable Long storeSizeBytes, @Nullable Long indexingTotal,
        @Nullable Long searchQueryTotal) {
}
