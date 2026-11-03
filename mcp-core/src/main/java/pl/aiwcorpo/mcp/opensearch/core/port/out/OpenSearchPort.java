package pl.aiwcorpo.mcp.opensearch.core.port.out;

import java.util.List;
import java.util.Optional;
import pl.aiwcorpo.mcp.opensearch.core.domain.AggregateQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.ClusterHealth;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexInfo;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexMapping;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogCount;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogPage;
import pl.aiwcorpo.mcp.opensearch.core.domain.QueryCheck;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogBuckets;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogQuery;

/** Outbound port to OpenSearch, implemented in mcp-integration. Every call runs with the caller's credential. */
public interface OpenSearchPort {

    /**
     * The body of {@code GET _resolve/index/<expression>} as OpenSearch sent it; the platform library reads it. A 404
     * (no index of the expression exists) is the library's empty answer. A 403 is a {@code NotFoundOrDeniedException}.
     */
    String resolveIndexResponse(String expression);

    /**
     * Searches the concrete {@code indices}, named one by one (never a pattern); the query also restricts
     * {@code _index} to {@code scope}. The page carries the position after its last raw hit when it is full.
     */
    LogPage search(List<String> indices, IndexScope scope, LogQuery query);

    /**
     * Counts the documents of the concrete {@code indices} by the field of the query. The indices are named one by
     * one (never a pattern), and the query again restricts {@code _index} to {@code scope}.
     */
    LogBuckets aggregate(List<String> indices, IndexScope scope, AggregateQuery query);

    /** Empty when the document or the index does not exist or is not visible to the caller. */
    Optional<IndexDocument> findDocument(String index, String id);

    /** Counts the documents of the query in the concrete {@code indices}. */
    LogCount count(List<String> indices, IndexScope scope, LogQuery query);

    /** Runs {@code _validate/query?explain} for the query on the concrete {@code indices}. */
    QueryCheck validate(List<String> indices, IndexScope scope, LogQuery query);

    /** Rows of {@code _cat/indices} for the concrete {@code indices}, in the order given; names OpenSearch omits are skipped. */
    List<IndexInfo> indexInfo(List<String> indices);

    /** Empty when the index does not exist or is not visible to the caller. */
    Optional<IndexMapping> findMapping(String index);

    /** {@code GET _cluster/health}. */
    ClusterHealth clusterHealth();
}
