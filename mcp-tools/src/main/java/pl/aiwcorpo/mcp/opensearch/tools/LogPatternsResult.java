package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.List;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

/**
 * Result of {@code find_log_patterns}. Always a sample: {@code sampledMessages} is what was read, never the number of
 * matching documents, and the counts are counts within the sample. Pattern and example are {@code UntrustedText}.
 *
 * @param sampleLimit    the largest number of messages this call was allowed to read
 * @param otherPatterns  patterns of the sample that are not listed
 * @param otherMessages  messages of the sample in those patterns
 */
public record LogPatternsResult(String indexPrefix, String from, String to, boolean sample, int sampledMessages,
        int sampleLimit, List<PatternView> patterns, long otherPatterns, long otherMessages, String note) {

    /** One group of messages that differ only in numbers, ids, addresses and timestamps. */
    public record PatternView(UntrustedText pattern, long count, UntrustedText example) {
    }

    static final String NOTE = "Computed from a sample of the newest matching messages, not from all of them. Numbers,"
            + " hexadecimal ids, UUIDs, IP addresses and timestamps are replaced by <NUM>, <HEX>, <UUID>, <IP> and <TS>.";
}
