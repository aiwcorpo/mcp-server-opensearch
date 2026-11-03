package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.List;

/** Fields of one index; {@code truncated} when the field limit cut the list. */
public record IndexMapping(String index, List<MappingField> fields, boolean truncated) {

    public IndexMapping {
        fields = List.copyOf(fields);
    }
}
