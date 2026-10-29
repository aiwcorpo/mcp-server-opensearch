package pl.aiwcorpo.mcp.opensearch.application.service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The date in the name of a rotated index, by the convention {@code p-YYYY.MM.DD} (a day) and {@code p-YYYY.MM} (a
 * month), in UTC. An index covers its day or month; a name without such an ending, or with a date that does not
 * exist, has no date and always meets any time range.
 */
final class IndexDates {

    private static final Pattern ENDING = Pattern.compile("-(\\d{4})\\.(\\d{2})(?:\\.(\\d{2}))?$");

    private IndexDates() {
    }

    /** Whether the period the name stands for overlaps {@code [from, to)}. */
    static boolean meets(String index, Instant from, Instant to) {
        Matcher ending = ENDING.matcher(index);
        if (!ending.find()) {
            return true;
        }
        try {
            LocalDate first = LocalDate.of(Integer.parseInt(ending.group(1)), Integer.parseInt(ending.group(2)),
                    ending.group(3) == null ? 1 : Integer.parseInt(ending.group(3)));
            LocalDate next = ending.group(3) == null ? first.plusMonths(1) : first.plusDays(1);
            Instant start = first.atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant end = next.atStartOfDay().toInstant(ZoneOffset.UTC);
            return start.isBefore(to) && end.isAfter(from);
        } catch (DateTimeException notADate) {
            return true;
        }
    }
}
