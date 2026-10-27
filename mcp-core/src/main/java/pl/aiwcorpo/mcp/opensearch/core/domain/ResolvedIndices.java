package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.List;

/**
 * What an index expression stands for in OpenSearch ({@code GET _resolve/index}): the concrete indices, the
 * aliases with the indices behind them, and the names of data streams.
 */
public record ResolvedIndices(List<Index> indices, List<Alias> aliases, List<String> dataStreams) {

    public ResolvedIndices {
        indices = List.copyOf(indices);
        aliases = List.copyOf(aliases);
        dataStreams = List.copyOf(dataStreams);
    }

    public boolean isEmpty() {
        return indices.isEmpty() && aliases.isEmpty() && dataStreams.isEmpty();
    }

    /** @param hidden whether OpenSearch reports the index with the {@code hidden} attribute */
    public record Index(String name, boolean hidden) {
    }

    public record Alias(String name, List<String> indices) {

        public Alias {
            indices = List.copyOf(indices);
        }
    }
}
