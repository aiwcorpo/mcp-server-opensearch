package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.Map;

/** One document of an index, reduced to the configured result fields and cleaned as untrusted text. */
public record IndexDocument(String index, String id, Map<String, Object> data) {
}
