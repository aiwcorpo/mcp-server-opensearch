package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * {@code GET /} fails (5xx): the state is unavailable and no tool that talks to OpenSearch is tried. The property only
 * gives this class a context of its own.
 */
@TestPropertySource(properties = "opensearch.search.call-budget=21s")
class UnavailableBackendTest extends ServerTestSupport {

    private static final String PAT = "Bearer opensearch-token-of-jkowalski-5c1e";

    @BeforeAll
    static void rootFails() {
        BACKEND.rootStatus(503);
    }

    @AfterAll
    static void restoreRoot() {
        BACKEND.rootStatus(200);
    }

    @Override
    boolean probeAnswers() {
        return false;
    }

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void everyToolThatNeedsOpenSearchRefusesAndAsksNoOne() {
        Map<String, Object> range = Map.of("index_prefix", "logs-team-a", "from", "2026-10-27T08:00:00Z",
                "to", "2026-10-27T10:00:00Z");
        try (McpSyncClient client = clientWithCredential(PAT)) {
            Map<String, Object> search = new java.util.HashMap<>(range);
            search.put("text", "payment");
            Map<String, Object> aggregate = new java.util.HashMap<>(range);
            aggregate.put("group_by", "level");
            for (McpSchema.CallToolResult result : List.of(call(client, "search_logs", search),
                    call(client, "aggregate_logs", aggregate), call(client, "count_logs", range),
                    call(client, "get_document", Map.of("index", "logs-team-a-2026.10.27", "id", "Xk3n8ZIBdLrT0q1aHf2p")),
                    call(client, "get_index_mapping", Map.of("index", "logs-team-a-2026.10.27")),
                    call(client, "list_indices", Map.of("index_prefix", "logs-team-a")),
                    call(client, "get_index_stats", Map.of("index_prefix", "logs-team-a")),
                    call(client, "find_log_patterns", range),
                    call(client, "validate_query", range), call(client, "get_cluster_health", Map.of()))) {
                assertThat(result.isError()).isTrue();
                assertThat(text(result)).contains("\"error\":\"upstream_unavailable\"");
            }
            assertThat(text(call(client, "get_backend_info", Map.of()))).contains("\"state\":\"unavailable\"");
        }
        assertThat(BACKEND.requests().stream().filter(r -> r.path().endsWith("/_search") || r.path().contains("/_cat/")
                || r.path().endsWith("/_mapping") || r.path().contains("/_stats/") || r.path().endsWith("/_validate/query")
                || r.path().equals("/_cluster/health"))).isEmpty();
    }
}
