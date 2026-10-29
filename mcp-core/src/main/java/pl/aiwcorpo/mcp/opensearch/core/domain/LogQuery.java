package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.time.Instant;

/**
 * A validated {@code search_logs} call.
 *
 * @param prefix an index prefix {@code p}: the indices named {@code p} or {@code p-...} that the call may read
 * @param filter values to match; never Query DSL
 * @param from   start of the time range, inclusive
 * @param to     end of the time range, exclusive
 * @param size   number of hits, 1-100
 */
public record LogQuery(String prefix, LogFilter filter, Instant from, Instant to, int size) {
}
