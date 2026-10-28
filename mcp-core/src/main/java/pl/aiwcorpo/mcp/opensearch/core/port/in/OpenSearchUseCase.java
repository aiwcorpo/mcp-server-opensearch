package pl.aiwcorpo.mcp.opensearch.core.port.in;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import pl.aiwcorpo.mcp.opensearch.core.domain.AggregateQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogBucket;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.ResolvedIndex;

/** Inbound port: what the server can do, in business terms. Implemented in mcp-application, called by mcp-tools. */
public interface OpenSearchUseCase {

    /**
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.NotFoundOrDeniedException when the caller may query no index,
     *         when the pattern reaches an index outside the allowed prefixes (alias, hidden index) or when
     *         OpenSearch refuses it
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.IndexOutsideScopeException when no allowed prefix covers the pattern
     */
    List<LogHit> searchLogs(LogQuery query);

    /**
     * Counts by one field over the concrete indices the pattern names and {@code readable} accepts; counts cannot
     * be corrected after filtering, so what the caller may not read is left out of the query.
     *
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.AggregationOverAliasException when the pattern reaches an alias
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.TooManyIndicesException when more indices are readable than one request names
     */
    List<LogBucket> aggregateLogs(AggregateQuery query, Predicate<String> readable);

    /**
     * The index behind {@code index} when it is one concrete index (or an alias of exactly one) that the caller may
     * see and the scope covers; empty for everything else: missing, forbidden, hidden, outside the scope, an alias of
     * several indices or with a filter or routing, a data stream. The cause is deliberately not told apart.
     */
    Optional<ResolvedIndex> resolveIndex(String index);

    /**
     * Reads a document of the index {@code resolveIndex} returned.
     *
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.NotFoundOrDeniedException when the document does not exist or is not visible
     */
    IndexDocument getDocument(String index, String id);
}
