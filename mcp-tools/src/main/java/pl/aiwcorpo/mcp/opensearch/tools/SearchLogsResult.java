package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.List;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;

/**
 * Result of {@code search_logs}. No total count: a count could include documents the caller cannot read. Text
 * under {@code hits[].data} is {@code UntrustedText}.
 *
 * @param truncated entries were left out to keep the result under the size budget (fewer than {@code size} came
 *                  back although more matched); a narrower query or a smaller {@code size} avoids it
 */
public record SearchLogsResult(String indexPattern, String from, String to, int returned, List<LogHit> hits,
        boolean truncated) {

    /** Characters of text one result may carry; the platform refuses a result over 1 MB of UTF-8. */
    static final int TEXT_BUDGET = 250_000;

    static SearchLogsResult of(String indexPattern, String from, String to, List<LogHit> found) {
        List<LogHit> kept = new java.util.ArrayList<>();
        int used = 0;
        for (LogHit hit : found) {
            used += hit.textSize();
            if (used > TEXT_BUDGET && !kept.isEmpty()) {
                return new SearchLogsResult(indexPattern, from, to, kept.size(), kept, true);
            }
            kept.add(hit);
        }
        return new SearchLogsResult(indexPattern, from, to, kept.size(), kept, false);
    }
}
