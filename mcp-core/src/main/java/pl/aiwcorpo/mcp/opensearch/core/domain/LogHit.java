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
 * @param position  sort position of this hit, null when paging is off or the sort values cannot go into a cursor
 */
public record LogHit(String index, @Nullable String id, @Nullable String timestamp, List<LogField> data,
        boolean truncated, @Nullable SearchAfter position) {

    public LogHit {
        data = List.copyOf(data);
    }

    public LogHit(String index, @Nullable String id, @Nullable String timestamp, List<LogField> data,
            boolean truncated) {
        this(index, id, timestamp, data, truncated, null);
    }

    /** Upper estimate of the bytes this hit takes in the JSON result, see {@link LogField#jsonSize()}. */
    public int jsonSize() {
        return HIT_OVERHEAD + LogField.jsonBytes(index) + data.stream().mapToInt(LogField::jsonSize).sum();
    }

    /** Keys, {@code id} (at most 256 characters), {@code timestamp} and the array around {@code data}. */
    private static final int HIT_OVERHEAD = 400;
}
