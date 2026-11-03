package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.List;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

/**
 * Answer of {@code validate_query}.
 *
 * @param valid          whether OpenSearch accepted the query on every checked index
 * @param checked        number of indices OpenSearch reported on
 * @param invalidIndices names of indices that rejected it, at most {@value #MAX_INVALID}
 * @param explanation    how OpenSearch reads the query (Lucene form), of the first checked index
 */
public record QueryCheck(boolean valid, int checked, List<String> invalidIndices, UntrustedText explanation) {

    public static final int MAX_INVALID = 20;

    public QueryCheck {
        invalidIndices = List.copyOf(invalidIndices);
    }
}
