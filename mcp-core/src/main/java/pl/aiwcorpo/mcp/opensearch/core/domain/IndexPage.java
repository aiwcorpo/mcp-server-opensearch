package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** A page of {@code list_indices}: rows by name, and the last name when more indices follow. */
public record IndexPage(List<IndexInfo> indices, @Nullable String lastName) {

    public IndexPage {
        indices = List.copyOf(indices);
    }
}
