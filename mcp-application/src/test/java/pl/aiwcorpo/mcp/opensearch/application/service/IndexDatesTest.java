package pl.aiwcorpo.mcp.opensearch.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class IndexDatesTest {

    private static final Instant FROM = Instant.parse("2026-10-27T08:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-28T00:00:00Z");

    @Test
    void aDailyIndexMeetsTheRangeThatTouchesItsDay() {
        assertThat(IndexDates.meets("p-2026.10.27", FROM, TO)).isTrue();
        assertThat(IndexDates.meets("p-2026.10.26", FROM, TO)).isFalse();
        assertThat(IndexDates.meets("p-2026.10.28", FROM, TO)).as("the end is exclusive").isFalse();
        assertThat(IndexDates.meets("p-2026.10.26", Instant.parse("2026-10-26T23:59:59Z"), TO)).isTrue();
    }

    @Test
    void aMonthlyIndexCoversTheWholeMonth() {
        assertThat(IndexDates.meets("p-2026.10", FROM, TO)).isTrue();
        assertThat(IndexDates.meets("p-2026.09", FROM, TO)).isFalse();
        assertThat(IndexDates.meets("p-2026.11", FROM, TO)).isFalse();
        assertThat(IndexDates.meets("p-2026.09", Instant.parse("2026-09-30T23:00:00Z"), TO)).isTrue();
        assertThat(IndexDates.meets("p-2025.12", Instant.parse("2025-12-31T23:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"))).isTrue();
    }

    @Test
    void anIndexWithoutADateIsAlwaysInTheSet() {
        for (String name : new String[] {"p", "p-app", "p-2026", "p-2026.10.27-reindexed", "p-2026.1.5", "p-12026.10.27", "p2026.10"}) {
            assertThat(IndexDates.meets(name, FROM, TO)).as(name).isTrue();
        }
    }

    @Test
    void dateLikeEndingsThatAreNotDatesAreTreatedAsNoDate() {
        for (String name : new String[] {"p-2026.13.01", "p-2026.00", "p-2026.02.30", "p-0000.00.00", "p-9999.99.99"}) {
            assertThat(IndexDates.meets(name, FROM, TO)).as(name).isTrue();
        }
    }
}
