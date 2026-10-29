package pl.aiwcorpo.mcp.opensearch.core.port.out;

import java.util.List;
import java.util.Optional;
import pl.aiwcorpo.mcp.opensearch.core.domain.AggregateQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogBucket;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
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
     * {@code _index} to {@code scope}.
     */
    List<LogHit> search(List<String> indices, IndexScope scope, LogQuery query);

    /**
     * Counts the documents of the concrete {@code indices} by the field of the query. The indices are named one by
     * one (never a pattern), and the query again restricts {@code _index} to {@code scope}.
     */
    List<LogBucket> aggregate(List<String> indices, IndexScope scope, AggregateQuery query);

    /** Empty when the document or the index does not exist or is not visible to the caller. */
    Optional<IndexDocument> findDocument(String index, String id);
}
