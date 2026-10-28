package pl.aiwcorpo.mcp.opensearch.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogField;
import pl.aiwcorpo.mcp.platform.text.TextHygiene;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

/**
 * Document values on their way to the model. Log lines are written by anyone who can make an application log
 * something, so they are data at best and a prompt injection at worst: every value and every name taken from a
 * document becomes an {@link UntrustedText}. The text is not escaped (the client does not render it as HTML);
 * order as the starter prescribes: the JSON value as text, {@link TextHygiene#normalize}, the cut, the value built
 * from the cut text. Objects and lists are flattened to entries, bounded in depth, count and string length.
 */
final class UntrustedValues {

    static final int MAX_STRING = 2000;
    static final int MAX_PATH = 128;
    static final int MAX_ENTRIES = 100;
    static final int MAX_DEPTH = 6;

    /** The shape {@code get_document} accepts for an id; anything else is not handed out as an id. */
    static final Pattern DOCUMENT_ID = Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9_.:@+=-]{0,255}$");
    private static final Pattern TIMESTAMP = Pattern.compile("^[0-9][0-9TZ:.+-]{0,39}$");

    private UntrustedValues() {
    }

    /** The result of flattening: entries and whether the entry limit cut some off. */
    record Flattened(List<LogField> fields, boolean truncated) {
    }

    @Nullable
    static String documentId(@Nullable String id) {
        return id != null && DOCUMENT_ID.matcher(id).matches() ? id : null;
    }

    @Nullable
    static String timestamp(@Nullable Object value) {
        if (value == null || value instanceof Map || value instanceof List) {
            return null;
        }
        String text = String.valueOf(value);
        return TIMESTAMP.matcher(text).matches() ? text : null;
    }

    /** {@code picked} holds the configured fields by their configured name, in configuration order. */
    static Flattened flatten(Map<String, Object> picked) {
        List<LogField> fields = new ArrayList<>();
        boolean[] truncated = {false};
        for (Map.Entry<String, Object> field : picked.entrySet()) {
            add(fields, truncated, field.getKey(), "", field.getValue(), 0);
        }
        return new Flattened(fields, truncated[0]);
    }

    static UntrustedText text(String value) {
        String clean = TextHygiene.normalize(value);
        String kept = UntrustedText.cut(clean, MAX_STRING);
        return UntrustedText.plainCut(kept, kept.length() < clean.length());
    }

    private static UntrustedText path(String value) {
        String clean = TextHygiene.normalize(value);
        String kept = UntrustedText.cut(clean, MAX_PATH);
        return UntrustedText.plainCut(kept, kept.length() < clean.length());
    }

    private static void add(List<LogField> fields, boolean[] truncated, String name, String path, Object value,
            int depth) {
        if (value == null) {
            return;
        }
        if (fields.size() >= MAX_ENTRIES || (depth >= MAX_DEPTH && (value instanceof Map || value instanceof List))) {
            truncated[0] = true;
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                add(fields, truncated, name, path.isEmpty() ? String.valueOf(entry.getKey())
                        : path + "." + entry.getKey(), entry.getValue(), depth + 1);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                add(fields, truncated, name, path + "[" + i + "]", list.get(i), depth + 1);
            }
        } else {
            fields.add(new LogField(name, path.isEmpty() ? null : path(path), text(String.valueOf(value))));
        }
    }
}
