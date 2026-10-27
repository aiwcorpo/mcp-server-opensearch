package pl.aiwcorpo.mcp.opensearch.core.port.in;

import java.util.List;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogQuery;

/** Inbound port: what the server can do, in business terms. Implemented in mcp-application, called by mcp-tools. */
public interface OpenSearchUseCase {

    /**
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.IndexOutsideScopeException when no allowed prefix covers the pattern
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.NotFoundOrDeniedException when the pattern reaches an index
     *         outside the allowed prefixes (alias, hidden index) or OpenSearch refuses it
     */
    List<LogHit> searchLogs(LogQuery query);

    /**
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.NotFoundOrDeniedException when the document or index does not
     *         exist, is not visible, or the index is an alias leading outside the allowed prefixes
     */
    IndexDocument getDocument(String index, String id);
}
