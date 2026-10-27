package pl.aiwcorpo.mcp.opensearch.core.port.out;

import java.util.List;
import java.util.Optional;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.ResolvedIndices;

/** Outbound port to OpenSearch, implemented in mcp-integration. Every call runs with the caller's credential. */
public interface OpenSearchPort {

    /** Empty when OpenSearch does not show the expression to the caller (403/404). */
    Optional<ResolvedIndices> resolve(String expression);

    /**
     * Whether any index behind the alias carries a filter or a routing for it. A document read by id goes to the
     * concrete index and would skip them, so such an alias is not read. Not visible to the caller (403/404) is
     * a {@code NotFoundOrDeniedException}.
     */
    boolean aliasHasFilterOrRouting(String alias);

    /**
     * Searches the server-built {@code expression}; the query also restricts {@code _index} to {@code scope}, so
     * an alias created after {@link #resolve} still cannot bring in other indices.
     */
    List<LogHit> search(String expression, IndexScope scope, LogQuery query);

    /** Empty when the document or the index does not exist or is not visible to the caller. */
    Optional<IndexDocument> findDocument(String index, String id);
}
