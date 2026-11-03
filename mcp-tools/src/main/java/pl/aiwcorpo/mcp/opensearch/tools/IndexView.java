package pl.aiwcorpo.mcp.opensearch.tools;

import org.jspecify.annotations.Nullable;
import pl.aiwcorpo.mcp.platform.resource.ResourceRef;

/**
 * One row of {@code list_indices}. The name is a {@code @ResourceRef}, so the starter drops the rows of indices the
 * policy refuses even if the server let one through.
 */
public record IndexView(@ResourceRef(type = OpenSearchIndexResolver.TYPE) String name, @Nullable String health,
        @Nullable Long documents, @Nullable Long sizeBytes) {
}
