package pl.aiwcorpo.mcp.opensearch.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogField;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

class SearchLogsResultTest {

    private static LogHit hit(int textSize) {
        return new LogHit("logs-a", "id", null,
                List.of(new LogField("message", null, UntrustedText.plain("x".repeat(textSize), textSize))), false);
    }

    @Test
    void aResultOverTheTextBudgetKeepsTheNewestHitsAndSaysSo() {
        List<LogHit> found = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            found.add(hit(50_000));
        }

        SearchLogsResult result = SearchLogsResult.of("logs-a*", "f", "t", found);

        assertThat(result.truncated()).isTrue();
        assertThat(result.returned()).isEqualTo(result.hits().size()).isLessThan(10).isPositive();
        assertThat(result.hits().stream().mapToInt(LogHit::textSize).sum()).isLessThanOrEqualTo(SearchLogsResult.TEXT_BUDGET);
    }

    @Test
    void aSmallResultIsReturnedWhole() {
        SearchLogsResult result = SearchLogsResult.of("logs-a*", "f", "t", List.of(hit(10), hit(10)));

        assertThat(result.truncated()).isFalse();
        assertThat(result.returned()).isEqualTo(2);
    }
}
