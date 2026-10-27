package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.List;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;

/**
 * Result of {@code search_logs}. No total count: a count could include documents the caller cannot read. Text
 * under {@code hits[].data} is marked as {@code UntrustedText}.
 */
public record SearchLogsResult(String indexPattern, String from, String to, int returned, List<LogHit> hits) {
}
