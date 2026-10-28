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

    /** Characters of text this entry carries, for the size budget of a result. */
    public int textSize() {
        return name.length() + (path == null ? 0 : path.text().length()) + value.text().length();
    }
}
