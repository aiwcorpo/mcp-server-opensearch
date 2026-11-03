package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.SearchAfter;

/**
 * The hits of one {@code search_logs} page that fit the result budget. No total count and no count of returned hits:
 * either would include documents the caller cannot read, which the starter removes after the tool ran. Text under
 * {@code hits[].data} is {@code UntrustedText}.
 *
 * @param truncated    entries were left out to keep the result under the size budget (fewer than {@code size} came
 *                     back although more matched); the cursor then continues after the last kept hit
 * @param lastPosition sort position of the last kept hit, null when paging is off
 */
record SearchLogsResult(List<LogHitView> hits, boolean truncated, @Nullable SearchAfter lastPosition) {

    /**
     * Estimated JSON bytes one result may carry. The platform refuses a result over 1 MiB (1 048 576 bytes) as an
     * internal error, so the budget leaves room for the envelope and for the estimate being rough.
     */
    static final int JSON_BUDGET = 900_000;

    static SearchLogsResult of(List<LogHit> found) {
        List<LogHitView> kept = new ArrayList<>();
        SearchAfter last = null;
        int used = 0;
        for (LogHit hit : found) {
            used += hit.jsonSize();
            if (used > JSON_BUDGET && !kept.isEmpty()) {
                return new SearchLogsResult(kept, true, last);
            }
            kept.add(LogHitView.of(hit));
            last = hit.position();
        }
        return new SearchLogsResult(kept, false, last);
    }
}
