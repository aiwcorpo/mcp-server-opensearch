package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The policy (C7) and the scope of the grants on both tools, with the example bundle of the contracts: the groups
 * {@code mcp-opensearch-idx-logs-team-a} and {@code -b} grant those prefixes, an index tagged {@code restricted} by the
 * server configuration is forbidden, and every refusal looks like an index that does not exist.
 */
class ResourcePolicyTest extends ServerTestSupport {

    private static final String PAT = "Bearer opensearch-token-of-jkowalski-5c1e";
    private static final String DOC_ID = "Xk3n8ZIBdLrT0q1aHf2p";
    private static final String NOT_FOUND_OR_DENIED = "\"error\":\"not_found_or_denied\"";

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    private static Map<String, Object> search(String pattern) {
        Map<String, Object> args = new HashMap<>();
        args.put("index_prefix", pattern);
        args.put("text", "payment");
        args.put("from", "2026-10-27T08:00:00Z");
        args.put("to", "2026-10-27T10:00:00Z");
        return args;
    }

    private String missing(McpSyncClient client) {
        return text(call(client, "get_document", Map.of("index", "logs-team-a-missing", "id", DOC_ID)));
    }

    @Test
    void anIndexTaggedRestrictedIsNotFoundOrDeniedLikeAMissingOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult restricted = call(client, "get_document", Map.of("index", "logs-team-a-hr-1", "id", DOC_ID));

            assertThat(restricted.isError()).isTrue();
            assertThat(text(restricted)).contains(NOT_FOUND_OR_DENIED).isEqualTo(missing(client));
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.path().contains("/_doc/logs-team-a-hr") || r.path().startsWith("/logs-team-a-hr-1/_doc"));
    }

    @Test
    void aSearchDropsTheHitsOfARestrictedIndexAndKeepsTheOthers() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", search("logs-team-a"));

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).contains("logs-team-a-2026.10.27").doesNotContain("logs-team-a-hr-1", "payroll");
        }
    }

    @Test
    void searchingARestrictedIndexByItsNameAnswersLikeAMissingOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            String missing = text(call(client, "search_logs", search("logs-team-a-nothing"))).replace("logs-team-a-nothing", "PREFIX");
            McpSchema.CallToolResult restricted = call(client, "search_logs", search("logs-team-a-hr-1"));

            assertThat(restricted.isError()).isFalse();
            assertThat(text(restricted).replace("logs-team-a-hr-1", "PREFIX")).contains("\"items\":[]").isEqualTo(missing);
            assertThat(text(call(client, "search_logs", search("logs-team-b-stream"))).replace("logs-team-b-stream", "PREFIX"))
                    .as("a data stream by name").isEqualTo(missing);
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.path().endsWith("/_search"));
    }

    @Test
    void aCallerWithoutTheReadGroupGetsAccessDeniedAndNothingReachesOpenSearch() {
        try (McpSyncClient client = clientInGroups(PAT, GRANT_TEAM_A, GRANT_TEAM_B)) {
            for (String tool : List.of("search_logs", "get_document")) {
                Map<String, Object> args = tool.equals("search_logs") ? search("logs-team-a")
                        : Map.of("index", "logs-team-a-2026.10.27", "id", DOC_ID);
                McpSchema.CallToolResult result = call(client, tool, args);

                assertThat(result.isError()).isTrue();
                assertThat(text(result)).contains("\"error\":\"access_denied\"");
            }
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aCallerWithoutAnyGrantGetsEmptyResultsAndNothingIsQueried() {
        try (McpSyncClient client = clientInGroups(PAT, READ_GROUP)) {
            assertThat(missing(client)).contains(NOT_FOUND_OR_DENIED);
            assertThat(text(call(client, "get_document", Map.of("index", "logs-team-a-2026.10.27", "id", DOC_ID))))
                    .isEqualTo(missing(client));
            McpSchema.CallToolResult search = call(client, "search_logs", search("logs-team-a"));
            assertThat(search.isError()).as(text(search)).isFalse();
            assertThat(text(search)).contains("\"items\":[]");
            Map<String, Object> aggregate = new HashMap<>(search("logs-team-a"));
            aggregate.remove("text");
            aggregate.put("group_by", "level");
            McpSchema.CallToolResult counted = call(client, "aggregate_logs", aggregate);
            assertThat(counted.isError()).as(text(counted)).isFalse();
            assertThat(text(counted)).contains("\"buckets\":[]");
        }
        assertThat(BACKEND.requests()).as("an empty scope must not become an index expression that means every index").isEmpty();
    }

    @Test
    void theQueryIsLimitedToTheGrantedPrefixes() {
        try (McpSyncClient client = clientInGroups(PAT, READ_GROUP, GRANT_TEAM_A)) {
            assertThat(call(client, "search_logs", search("logs-team-a")).isError()).isFalse();
            assertThat(text(call(client, "search_logs", search("logs-team-b")))).contains("\"items\":[]");
        }
        StubBackend.Request search = BACKEND.requests().stream().filter(r -> r.path().endsWith("/_search")).findFirst().orElseThrow();
        assertThat(search.body()).contains("{\"term\":{\"_index\":\"logs-team-a\"}}", "{\"prefix\":{\"_index\":\"logs-team-a-\"}}")
                .doesNotContain("logs-team-b");
        assertThat(BACKEND.requests()).noneMatch(r -> r.decodedPath().contains("logs-team-b"));
    }

    @Test
    void aPrefixNeverReachesItsNeighbour() {
        try (McpSyncClient client = clientInGroups(PAT, READ_GROUP, GRANT_TEAM_A)) {
            McpSchema.CallToolResult result = call(client, "search_logs", search("logs-team-a"));

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).doesNotContain("logs-team-ab-1", "neighbour");
            assertThat(text(call(client, "search_logs", search("logs-team-ab")))).contains("\"items\":[]");
            assertThat(text(call(client, "get_document", Map.of("index", "logs-team-ab-1", "id", DOC_ID)))).contains(NOT_FOUND_OR_DENIED);
        }
        assertThat(BACKEND.requests().getFirst().decodedPath()).isEqualTo("/_resolve/index/logs-team-a,logs-team-a-*");
        assertThat(BACKEND.requests()).noneMatch(r -> r.decodedPath().contains("logs-team-ab"));
    }
}
