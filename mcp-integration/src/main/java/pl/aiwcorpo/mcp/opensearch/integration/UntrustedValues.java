package pl.aiwcorpo.mcp.opensearch.integration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.aiwcorpo.mcp.opensearch.core.domain.UntrustedText;

/**
 * Document values on their way to the model. Log lines are written by anyone who can make an application log
 * something, so they are data at best and a prompt injection at worst: every string becomes an
 * {@link UntrustedText}, markup is escaped (no raw HTML reaches the client), control characters other than tab
 * and newline are dropped, and size is bounded.
 */
final class UntrustedValues {

    static final int MAX_STRING = 2000;
    static final int MAX_ITEMS = 50;
    static final int MAX_DEPTH = 6;
    private static final String TRUNCATED = "...[truncated]";

    private UntrustedValues() {
    }

    static Map<String, Object> cleanObject(Map<?, ?> source) {
        return cleanMap(source, 0);
    }

    /** For identifiers and timestamps, which stay plain strings: cleaned, not wrapped. */
    static String cleanText(String value) {
        if (value == null) {
            return null;
        }
        Cleaned cleaned = sanitize(value);
        return cleaned.truncated() ? cleaned.text() + TRUNCATED : cleaned.text();
    }

    static UntrustedText text(String value) {
        Cleaned cleaned = sanitize(value);
        return UntrustedText.plain(cleaned.text(), cleaned.truncated());
    }

    private record Cleaned(String text, boolean truncated) {
    }

    private static Cleaned sanitize(String value) {
        StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_STRING) + 16);
        boolean truncated = false;
        for (int i = 0; i < value.length(); i++) {
            if (out.length() >= MAX_STRING) {
                truncated = true;
                break;
            }
            char c = value.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                default -> {
                    if (c == '\n' || c == '\t' || !Character.isISOControl(c)) {
                        out.append(c);
                    }
                }
            }
        }
        if (out.length() > MAX_STRING) {
            out.setLength(MAX_STRING);
            truncated = true;
        }
        return new Cleaned(out.toString(), truncated);
    }

    private static Object clean(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (depth >= MAX_DEPTH) {
            return TRUNCATED;
        }
        if (value instanceof Map<?, ?> map) {
            return cleanMap(map, depth + 1);
        }
        if (value instanceof List<?> list) {
            List<Object> cleaned = new ArrayList<>();
            for (Object item : list.subList(0, Math.min(list.size(), MAX_ITEMS))) {
                cleaned.add(clean(item, depth + 1));
            }
            if (list.size() > MAX_ITEMS) {
                cleaned.add(TRUNCATED);
            }
            return cleaned;
        }
        return text(String.valueOf(value));
    }

    private static Map<String, Object> cleanMap(Map<?, ?> map, int depth) {
        Map<String, Object> cleaned = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count++ >= MAX_ITEMS) {
                cleaned.put("_truncated", true);
                break;
            }
            String key = cleanText(String.valueOf(entry.getKey()));
            cleaned.put(key.length() > 128 ? key.substring(0, 128) : key, clean(entry.getValue(), depth));
        }
        return cleaned;
    }
}
