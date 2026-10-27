package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.Map;

/** Result of {@code get_document}: the configured fields of the document, marked as data like in {@link SearchLogsResult}. */
public record DocumentResult(String index, String id, Map<String, Object> data, String dataNote) {
}
