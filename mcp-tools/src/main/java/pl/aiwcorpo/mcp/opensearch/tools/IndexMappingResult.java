package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.List;
import pl.aiwcorpo.mcp.opensearch.core.domain.MappingField;

/** Result of {@code get_index_mapping}: field paths are {@code UntrustedText}, the type is one of the mapping types. */
public record IndexMappingResult(String index, List<MappingField> fields, boolean truncated) {
}
