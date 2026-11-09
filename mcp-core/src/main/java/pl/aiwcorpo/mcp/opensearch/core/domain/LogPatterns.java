package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Groups log messages that differ only in variable parts. The variable parts are masked in a fixed order, because a
 * later rule would otherwise cut an earlier match apart (the digits of a timestamp or an address): timestamps, UUIDs,
 * IPv4 addresses, hexadecimal ids, plain numbers. Every rule is a linear scan; the messages are already cut at 2000 characters.
 */
public final class LogPatterns {

    public static final String TIMESTAMP = "<TS>";
    public static final String UUID = "<UUID>";
    public static final String IP = "<IP>";
    public static final String HEX = "<HEX>";
    public static final String NUMBER = "<NUM>";

    /** Longest pattern and example kept, in characters. */
    public static final int MAX_TEXT = 300;

    private static final Pattern TIMESTAMP_RULE = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}(?::?\\d{2})?)?");
    private static final Pattern UUID_RULE = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern IP_RULE = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?\\b");
    /** {@code 0x...}, or a run of 8 or more hex digits that holds both a digit and a letter (so neither a number nor a word like "feedback"). */
    private static final Pattern HEX_RULE = Pattern.compile(
            "\\b0[xX][0-9a-fA-F]+\\b|\\b(?=[0-9a-fA-F]*[0-9])(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{8,}\\b");
    private static final Pattern NUMBER_RULE = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /**
     * @param pattern the masked message
     * @param count   messages of the sample with this pattern
     * @param example the first message of the sample with this pattern, as it was
     */
    public record Group(String pattern, long count, String example) {
    }

    /**
     * @param groups  the most frequent patterns, biggest first, at most the requested number
     * @param others  patterns that did not make the list
     * @param otherMessages messages of the sample in those patterns
     */
    public record Summary(List<Group> groups, long others, long otherMessages) {

        public Summary {
            groups = List.copyOf(groups);
        }
    }

    private LogPatterns() {
    }

    public static String mask(String message) {
        String masked = TIMESTAMP_RULE.matcher(message).replaceAll(TIMESTAMP);
        masked = UUID_RULE.matcher(masked).replaceAll(UUID);
        masked = IP_RULE.matcher(masked).replaceAll(IP);
        masked = HEX_RULE.matcher(masked).replaceAll(HEX);
        masked = NUMBER_RULE.matcher(masked).replaceAll(NUMBER);
        return SPACES.matcher(masked).replaceAll(" ").strip();
    }

    /** Ties keep the order in which the patterns first appeared, so the newest message wins when the sample is newest first. */
    public static Summary summarize(List<String> messages, int top) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, String> examples = new LinkedHashMap<>();
        for (String message : messages) {
            String pattern = mask(message);
            if (pattern.isEmpty()) {
                continue;
            }
            counts.computeIfAbsent(pattern, key -> new long[1])[0]++;
            examples.putIfAbsent(pattern, message);
        }
        List<Group> all = new ArrayList<>();
        counts.forEach((pattern, count) -> all.add(new Group(pattern, count[0], examples.get(pattern))));
        all.sort(Comparator.comparingLong(Group::count).reversed());
        List<Group> kept = all.subList(0, Math.min(top, all.size()));
        long otherMessages = all.stream().skip(kept.size()).mapToLong(Group::count).sum();
        return new Summary(kept, all.size() - kept.size(), otherMessages);
    }
}
