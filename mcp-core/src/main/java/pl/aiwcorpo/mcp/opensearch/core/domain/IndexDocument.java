package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One document of an index, reduced to the configured result fields.
 *
 * @param id        as in {@link LogHit}: null when the id has a shape {@code get_document} would not accept
 * @param truncated whether {@code data} was cut at the entry limit
 */
public record IndexDocument(String index, @Nullable String id, List<LogField> data, boolean truncated) {

    public IndexDocument {
        data = List.copyOf(data);
    }
}
