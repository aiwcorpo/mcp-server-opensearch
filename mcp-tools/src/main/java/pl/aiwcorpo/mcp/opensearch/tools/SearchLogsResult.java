package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.ArrayList;
import java.util.List;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;

/**
 * Result of {@code search_logs}. No total count and no count of returned hits: either would include documents the
 * caller cannot read, which the starter removes after the tool ran. Text under {@code hits[].data} is
 * {@code UntrustedText}.
 *
 * @param truncated entries were left out to keep the result under the size budget (fewer than {@code size} came
 *                  back although more matched); a narrower query or a smaller {@code size} avoids it
 */
public record SearchLogsResult(String indexPattern, String from, String to, List<LogHitView> hits, boolean truncated) {

    /**
     * Estimated JSON bytes one result may carry. The platform refuses a result over 1 MiB (1 048 576 bytes) as an
     * internal error, so the budget leaves room for the envelope and for the estimate being rough.
     */
    static final int JSON_BUDGET = 900_000;

    static SearchLogsResult of(String indexPattern, String from, String to, List<LogHit> found) {
        List<LogHitView> kept = new ArrayList<>();
        int used = 0;
        for (LogHit hit : found) {
            used += hit.jsonSize();
            if (used > JSON_BUDGET && !kept.isEmpty()) {
                return new SearchLogsResult(indexPattern, from, to, kept, true);
            }
            kept.add(LogHitView.of(hit));
        }
        return new SearchLogsResult(indexPattern, from, to, kept, false);
    }
}
