package pl.aiwcorpo.mcp.opensearch.core.port.in;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
     * Searches the concrete indices of the caller's scope that the prefix of the query covers and {@code readable}
     * accepts (the policy). Nothing to query, a prefix that covers no index the caller may read and an index
     * OpenSearch does not show all give the same empty list, so the result does not tell a restricted index from
     * a nonexistent one.
     *
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.TooManyIndicesException when more indices are readable than one request names
     */
    List<LogHit> searchLogs(LogQuery query, Predicate<String> readable);

    /**
     * Counts by one field over the same indices as {@link #searchLogs}; counts cannot be corrected after filtering,
     * so what the caller may not read is left out of the query. Aliases are not among the indices, so they add nothing.
     *
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.TooManyIndicesException when more indices are readable than one request names
     */
    List<LogBucket> aggregateLogs(AggregateQuery query, Predicate<String> readable);

    /**
     * The index named by {@code index} when it is a concrete index the caller may see inside the scope; empty for
     * everything else: missing, forbidden, hidden, outside the scope, an alias, a data stream. The cause is
     * deliberately not told apart.
     */
    Optional<ResolvedIndex> resolveIndex(String index);

    /** The subset of {@code indices} that {@link #resolveIndex} accepts, from one resolution instead of one per name. */
    Set<String> resolveIndices(Collection<String> indices);

    /**
     * Reads a document of the index {@code resolveIndex} returned.
     *
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.NotFoundOrDeniedException when the document does not exist or is not visible
     */
    IndexDocument getDocument(String index, String id);
}
