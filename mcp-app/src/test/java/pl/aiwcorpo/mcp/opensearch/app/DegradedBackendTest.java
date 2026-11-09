package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * A cluster that refuses an anonymous {@code GET /}: the version is not known, so the server runs in degraded mode. The
 * core tools keep working, the ones that need verified APIs refuse. The property below only gives this class a context
 * of its own.
 */
@TestPropertySource(properties = "opensearch.backend.assume-tested-version=false")
class DegradedBackendTest extends ServerTestSupport {

    private static final String PAT = "Bearer opensearch-token-of-jkowalski-5c1e";
    private static final String UNAVAILABLE = "\"error\":\"upstream_unavailable\"";

    /** Runs before the context of this class is built, so the first probe meets the refusal. */
    @BeforeAll
    static void clusterRefusesAnonymousRoot() {
        BACKEND.anonymousRoot(false);
    }

    @AfterAll
    static void restoreRoot() {
        BACKEND.anonymousRoot(true);
    }

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void theStateIsDegradedAndSaysWhy() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_backend_info", Map.of());

            assertThat(text(result)).contains("\"state\":\"degraded\"", "\"version\":null", "the root endpoint refused an anonymous request",
                    "\"search\"", "\"get_document\"").doesNotContain("cluster_health", "list_indices", "validate_query", "index_stats");
        }
        assertThat(BACKEND.probeAuthorizations()).as("the probe asked anonymously").contains("null");
    }

    @Test
    void theToolsThatNeedVerifiedApisRefuseWithAFixedMessageAndAskNoOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            Map<String, Object> range = Map.of("index_prefix", "logs-team-a", "from", "2026-10-27T08:00:00Z",
                    "to", "2026-10-27T10:00:00Z");
            for (McpSchema.CallToolResult result : List.of(call(client, "get_cluster_health", Map.of()),
                    call(client, "list_indices", Map.of("index_prefix", "logs-team-a")),
                    call(client, "get_index_stats", Map.of("index_prefix", "logs-team-a")),
                    call(client, "validate_query", range))) {
                assertThat(result.isError()).isTrue();
                assertThat(text(result)).contains(UNAVAILABLE, "get_backend_info");
            }
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void theCoreToolsKeepWorking() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult search = call(client, "search_logs", Map.of("index_prefix", "logs-team-a", "text", "payment",
                    "from", "2026-10-27T08:00:00Z", "to", "2026-10-27T10:00:00Z"));
            McpSchema.CallToolResult count = call(client, "count_logs", Map.of("index_prefix", "logs-team-a",
                    "from", "2026-10-27T08:00:00Z", "to", "2026-10-27T10:00:00Z"));
            McpSchema.CallToolResult mapping = call(client, "get_index_mapping", Map.of("index", "logs-team-a-2026.10.27"));

            assertThat(search.isError()).as(text(search)).isFalse();
            assertThat(count.isError()).as(text(count)).isFalse();
            assertThat(mapping.isError()).as(text(mapping)).isFalse();
        }
    }
}
