package pl.aiwcorpo.mcp.opensearch.core.port.out;

import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;

/**
 * The single place that decides which index prefixes the current call may touch. M1: the prefixes from the
 * server configuration. Starter beta (M3): that list narrowed by the caller's {@code QueryScope}
 * ({@code opensearchIndexPrefixes} from the policy bundle and the virtual server's {@code vsc}).
 */
public interface IndexScopeProvider {

    IndexScope current();
}
