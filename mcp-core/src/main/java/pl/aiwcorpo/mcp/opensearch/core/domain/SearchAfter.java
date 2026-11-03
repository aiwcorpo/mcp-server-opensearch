package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Position in the sorted result of {@code search_logs}: the values of the two sort keys of the last hit returned
 * (timestamp, then the configured tiebreaker field). It is what OpenSearch calls {@code search_after}.
 *
 * @param timestamp  sort value of the timestamp field as OpenSearch sends it (epoch milliseconds, or nanoseconds for a
 *                   {@code date_nanos} field)
 * @param tiebreaker sort value of the unique field, printable ASCII so it survives the cursor unchanged
 */
public record SearchAfter(long timestamp, String tiebreaker) {

    private static final Pattern TIEBREAKER = Pattern.compile("^[\\x20-\\x7e]{1,256}$");

    public SearchAfter {
        if (!TIEBREAKER.matcher(tiebreaker).matches()) {
            throw new IllegalArgumentException("tiebreaker is not 1-256 printable ASCII characters");
        }
    }

    /** Empty when the sort value cannot be carried in a cursor; the caller then issues no cursor. */
    public static java.util.Optional<SearchAfter> of(@Nullable Object timestamp, @Nullable Object tiebreaker) {
        if (!(timestamp instanceof Number number) || number instanceof Double || number instanceof Float
                || tiebreaker == null) {
            return java.util.Optional.empty();
        }
        String text = String.valueOf(tiebreaker);
        return TIEBREAKER.matcher(text).matches()
                ? java.util.Optional.of(new SearchAfter(number.longValue(), text)) : java.util.Optional.empty();
    }

    /** The text kept in a cursor: {@code <timestamp>|<tiebreaker>}. */
    public String encode() {
        return timestamp + "|" + tiebreaker;
    }

    /** @throws IllegalArgumentException when the text is not what {@link #encode()} writes */
    public static SearchAfter decode(String text) {
        int bar = text.indexOf('|');
        if (bar <= 0) {
            throw new IllegalArgumentException("not a position");
        }
        return new SearchAfter(Long.parseLong(text.substring(0, bar)), text.substring(bar + 1));
    }
}
