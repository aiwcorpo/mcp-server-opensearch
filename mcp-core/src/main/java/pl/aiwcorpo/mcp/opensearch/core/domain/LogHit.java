package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One log document, reduced to the configured result fields.
 *
 * @param id        the document id when it has the shape {@code get_document} accepts, otherwise null (an id is
 *                  free text up to 512 bytes and comes from whoever shipped the log)
 * @param timestamp value of the configured timestamp field when it looks like a date or a number, or null
 * @param data      the configured fields of {@code _source}, as text
 * @param truncated whether {@code data} was cut at the entry limit
 */
public record LogHit(String index, @Nullable String id, @Nullable String timestamp, List<LogField> data,
        boolean truncated) {

    public LogHit {
        data = List.copyOf(data);
    }

    public int textSize() {
        return data.stream().mapToInt(LogField::textSize).sum() + index.length();
    }
}
