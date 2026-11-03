package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A validated {@code search_logs}, {@code count_logs} or {@code validate_query} call.
 *
 * @param prefix an index prefix {@code p}: the indices named {@code p} or {@code p-...} that the call may read
 * @param filter values to match; never Query DSL
 * @param from   start of the time range, inclusive
 * @param to     end of the time range, exclusive
 * @param size   number of hits, 1-100 (ignored by count and validate)
 * @param after  where the previous page ended, or null for the first page
 */
public record LogQuery(String prefix, LogFilter filter, Instant from, Instant to, int size,
        @Nullable SearchAfter after) {

    public LogQuery(String prefix, LogFilter filter, Instant from, Instant to, int size) {
        this(prefix, filter, from, to, size, null);
    }
}
