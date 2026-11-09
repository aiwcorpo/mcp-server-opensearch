package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** A page of {@code get_index_stats}: rows by name, and the last name when more indices follow. */
public record IndexStatsPage(List<IndexStats> indices, @Nullable String lastName) {

    public IndexStatsPage {
        indices = List.copyOf(indices);
    }
}
