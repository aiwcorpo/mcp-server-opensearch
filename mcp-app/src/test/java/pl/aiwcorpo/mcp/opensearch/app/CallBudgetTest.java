package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The wall-clock budget of one {@code search_logs} call: once it is spent, no further backend page is read. */
@TestPropertySource(properties = "opensearch.search.call-budget=1ns")
class CallBudgetTest extends ServerTestSupport {

    private static final String PAT = "Bearer opensearch-token-of-jkowalski-5c1e";

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void aSpentBudgetStopsAfterTheFirstRead() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", Map.of("index_prefix", "logs-team-a",
                    "text", "all-hidden-always", "from", "2026-10-27T08:00:00Z", "to", "2026-10-27T10:00:00Z", "size", 2));

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).contains("\"items\":[]");
        }
        assertThat(BACKEND.requests().stream().filter(r -> r.path().endsWith("/_search"))).hasSize(1);
    }
}
