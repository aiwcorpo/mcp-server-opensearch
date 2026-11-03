package pl.aiwcorpo.mcp.opensearch.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogField;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.SearchAfter;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;
import tools.jackson.databind.json.JsonMapper;

class SearchLogsResultTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static LogHit hit(int textSize) {
        return new LogHit("logs-a", "id", null,
                List.of(new LogField("message", null, UntrustedText.plain("x".repeat(textSize), textSize))), false);
    }

    @Test
    void aResultOverTheTextBudgetKeepsTheNewestHitsAndSaysSo() {
        List<LogHit> found = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            found.add(hit(200_000));
        }

        SearchLogsResult result = SearchLogsResult.of(found);

        assertThat(result.truncated()).isTrue();
        assertThat(result.hits().size()).isLessThan(10).isPositive();
        assertThat(found.subList(0, result.hits().size()).stream().mapToInt(LogHit::jsonSize).sum())
                .isLessThanOrEqualTo(SearchLogsResult.JSON_BUDGET);
    }

    @Test
    void manyShortEntriesAreCountedWithTheirJsonOverhead() {
        List<LogHit> found = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            List<LogField> fields = new ArrayList<>();
            for (int entry = 0; entry < 100; entry++) {
                fields.add(new LogField("message", UntrustedText.plain("[" + entry + "]", 20), UntrustedText.plain("x", 1)));
            }
            found.add(new LogHit("logs-a", "id" + i, "2026-10-27T09:15:02.123Z", fields, false));
        }

        SearchLogsResult result = SearchLogsResult.of(found);

        assertThat(result.truncated()).isTrue();
        assertThat(result.hits().size()).isPositive().isLessThan(100);
        assertThat(JSON.writeValueAsBytes(result.hits()).length).as("the platform limit is 1 048 576 bytes")
                .isLessThan(1_048_576);
    }

    @Test
    void aSmallResultIsReturnedWhole() {
        SearchLogsResult result = SearchLogsResult.of(List.of(hit(10), hit(10)));

        assertThat(result.truncated()).isFalse();
        assertThat(result.hits()).hasSize(2);
    }

    @Test
    void aTruncatedResultResumesAfterTheLastHitItKept() {
        List<LogHit> found = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            found.add(new LogHit("logs-a", "id" + i, null,
                    List.of(new LogField("message", null, UntrustedText.plain("x".repeat(200_000), 200_000))), false,
                    new SearchAfter(1000 - i, "doc-" + i)));
        }

        SearchLogsResult result = SearchLogsResult.of(found);

        assertThat(result.truncated()).isTrue();
        assertThat(result.lastPosition()).isEqualTo(found.get(result.hits().size() - 1).position());
    }

    @Test
    void aPositionRoundTripsThroughTheTextOfACursor() {
        SearchAfter position = new SearchAfter(1761556502123L, "evt|7 x");

        assertThat(SearchAfter.decode(position.encode())).isEqualTo(position);
        assertThat(SearchAfter.of(1761556502123L, "evt-1")).contains(new SearchAfter(1761556502123L, "evt-1"));
    }

    @Test
    void sortValuesThatCannotTravelInACursorGiveNoPosition() {
        assertThat(SearchAfter.of(1.5d, "x")).isEmpty();
        assertThat(SearchAfter.of(null, "x")).isEmpty();
        assertThat(SearchAfter.of(1L, null)).isEmpty();
        assertThat(SearchAfter.of(1L, "line\nbreak")).isEmpty();
        assertThat(SearchAfter.of(1L, "x".repeat(257))).isEmpty();
    }
}
