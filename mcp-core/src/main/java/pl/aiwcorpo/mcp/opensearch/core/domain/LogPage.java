package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of {@code search_logs}.
 *
 * @param hits hits inside the caller's scope; may be fewer than asked for, or none
 * @param next position after the last hit OpenSearch returned when the page was full (so more may follow), else null.
 *             Taken from the raw answer, so a page whose hits were all dropped still lets the caller go on.
 */
public record LogPage(List<LogHit> hits, @Nullable SearchAfter next) {

    public LogPage {
        hits = List.copyOf(hits);
    }

    public static LogPage empty() {
        return new LogPage(List.of(), null);
    }
}
