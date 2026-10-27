package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.Map;

/** Result of {@code get_document}: the configured fields of the document, text marked as {@code UntrustedText}. */
public record DocumentResult(String index, String id, Map<String, Object> data) {
}
