package pl.aiwcorpo.mcp.opensearch.tools;

import org.jspecify.annotations.Nullable;
import pl.aiwcorpo.mcp.platform.resource.ResourceRef;

/**
 * One row of {@code list_indices}. The name is a {@code @ResourceRef}, so the starter drops the rows of indices the
 * policy refuses even if the server let one through. No document count or size: those statistics ignore document level
 * security.
 */
public record IndexView(@ResourceRef(type = OpenSearchIndexResolver.TYPE) String name, @Nullable String health) {
}
