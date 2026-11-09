package pl.aiwcorpo.mcp.opensearch.core.port.in;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import pl.aiwcorpo.mcp.opensearch.core.domain.AggregateQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.ClusterHealth;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexMapping;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexPage;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexStatsPage;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogCount;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogPage;
import pl.aiwcorpo.mcp.opensearch.core.domain.QueryCheck;
import org.jspecify.annotations.Nullable;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogBuckets;
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
    LogPage searchLogs(LogQuery query, Predicate<String> readable);

    /**
     * Counts by one field over the same indices as {@link #searchLogs}; counts cannot be corrected after filtering,
     * so what the caller may not read is left out of the query. Aliases are not among the indices, so they add nothing.
     *
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.TooManyIndicesException when more indices are readable than one request names
     */
    LogBuckets aggregateLogs(AggregateQuery query, Predicate<String> readable);

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

    /**
     * Counts the documents of the query over the same indices as {@link #searchLogs}, minus what the policy refuses.
     *
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.TooManyIndicesException when more indices are readable than one request names
     */
    LogCount countLogs(LogQuery query, Predicate<String> readable);

    /**
     * Asks OpenSearch whether it accepts the query that {@link #searchLogs} would send, and how it reads it.
     *
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.TooManyIndicesException when more indices are readable than one request names
     */
    QueryCheck validateQuery(LogQuery query, Predicate<String> readable);

    /**
     * Indices of the scope under {@code prefix} that the policy accepts, by name, starting after {@code after} (exclusive,
     * null for the first page). At most {@code limit} rows; {@code lastName} of the page is set when more follow.
     */
    IndexPage listIndices(String prefix, @Nullable String after, int limit, Predicate<String> readable);

    /** Statistics of the same indices {@link #listIndices} would list, paged the same way. */
    IndexStatsPage indexStats(String prefix, @Nullable String after, int limit, Predicate<String> readable);

    /** Fields of the index {@link #resolveIndex} returned. */
    IndexMapping getIndexMapping(String index);

    /** Cluster-wide health. */
    ClusterHealth clusterHealth();
}
