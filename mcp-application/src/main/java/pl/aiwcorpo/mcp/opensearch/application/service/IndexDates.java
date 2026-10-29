package pl.aiwcorpo.mcp.opensearch.application.service;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The date in the name of a rotated index, by the convention {@code p-YYYY.MM.DD} (a day) and {@code p-YYYY.MM} (a
 * month). The date is read as UTC; a cluster that rotates in another zone names the day of its own clock, which can
 * differ by up to a day, so the range is widened by {@link #MARGIN} on both sides before the comparison. The widening
 * only keeps an index in the set (the filter never adds a name the scope did not list). A name without such an
 * ending, or with a date that does not exist, has no date and always meets any time range.
 */
final class IndexDates {

    /** Covers any offset between the zone the index names were written in and UTC. */
    static final Duration MARGIN = Duration.ofDays(1);

    private static final Pattern ENDING = Pattern.compile("-(\\d{4})\\.(\\d{2})(?:\\.(\\d{2}))?$");

    private IndexDates() {
    }

    /** Whether the period the name stands for overlaps {@code [from, to)} widened by {@link #MARGIN}. */
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
            return start.isBefore(to.plus(MARGIN)) && end.isAfter(from.minus(MARGIN));
        } catch (DateTimeException notADate) {
            return true;
        }
    }
}
