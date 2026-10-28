package pl.aiwcorpo.mcp.opensearch.core.domain;

import org.jspecify.annotations.Nullable;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

/**
 * One value of a document field. A result field that holds an object or a list becomes several entries, one per
 * leaf, so the result stays a record (a map has no bounded schema) and the names inside a document, which anyone
 * who can write a log line chooses, are marked as untrusted too.
 *
 * @param name  the configured result field, from the server configuration
 * @param path  where the value sits below {@code name} ({@code a.b}, {@code [2]}), written by the document; null
 *              for the field's own value
 * @param value the value as text; numbers and booleans in their JSON spelling
 */
public record LogField(String name, @Nullable UntrustedText path, UntrustedText value) {

    /**
     * Upper estimate of the bytes this entry takes in the JSON result: the fixed part ({@code name}, two
     * {@code UntrustedText} objects with {@code kind}, {@code format} and {@code truncated}, measured at about 190
     * bytes for a one-character value) rounded up, plus the text as UTF-8 with quotes, backslashes and line breaks
     * counted twice. Counting characters alone let a document with many short entries pass a budget that the
     * serialized result then exceeded by a factor of two.
     */
    public int jsonSize() {
        return ENTRY_OVERHEAD + jsonBytes(name) + (path == null ? 0 : jsonBytes(path.text())) + jsonBytes(value.text());
    }

    private static final int ENTRY_OVERHEAD = 250;

    static int jsonBytes(String text) {
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\' || c == '\n' || c == '\t' || c == '\r' || c == '\b' || c == '\f') {
                bytes += 2;
            } else if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else {
                // 3 bytes for a BMP character; a surrogate pair is 4 bytes for two units, so 2 per unit fits.
                bytes += 3;
            }
        }
        return bytes;
    }
}
