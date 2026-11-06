package pl.aiwcorpo.mcp.opensearch.core.port.out;

import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;

/**
 * The single place that decides which index prefixes the current call may touch: the prefixes this server is
 * configured for, narrowed by the caller's {@code QueryScope} (the {@code OpenSearch::Index} scopes the
 * administrator mapped to the caller's groups). Empty when nothing is left.
 */
public interface IndexScopeProvider {

    IndexScope current();
}
