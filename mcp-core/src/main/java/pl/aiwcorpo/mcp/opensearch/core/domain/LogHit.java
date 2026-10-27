package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.Map;

/**
 * One log document, reduced to the configured result fields.
 *
 * @param timestamp value of the configured timestamp field as OpenSearch stored it, or null
 * @param data      the configured fields of {@code _source}, already cleaned as untrusted text
 */
public record LogHit(String index, String id, String timestamp, Map<String, Object> data) {
}
