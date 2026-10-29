package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code aggregate_logs}: counts only over concrete indices the caller may read, never over an alias or a pattern. */
class AggregateLogsToolTest extends ServerTestSupport {

    private static final String PAT = "Bearer opensearch-token-of-jkowalski-5c1e";
    private static final String NOT_FOUND_OR_DENIED = "\"error\":\"not_found_or_denied\"";

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    private static Map<String, Object> args(String pattern) {
        Map<String, Object> args = new HashMap<>();
        args.put("index_pattern", pattern);
        args.put("group_by", "level");
        args.put("from", "2026-10-27T08:00:00Z");
        args.put("to", "2026-10-27T10:00:00Z");
        return args;
    }

    private static StubBackend.Request searchRequest() {
        return BACKEND.requests().stream().filter(r -> r.path().endsWith("/_search")).findFirst().orElseThrow();
    }

    @Test
    void countsTheReadableIndicesOnlyAndMarksTheGroupsAsUntrusted() {
        Map<String, Object> arguments = args("logs-team-a");
        arguments.put("level", "ERROR");
        arguments.put("text", "payment failed");
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "aggregate_logs", arguments);

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).contains("\"groupBy\":\"level\"", "\"count\":7", "\"text\":\"ERROR\"",
                    "\"text\":\"WARN <script>\"", "\"kind\":\"untrusted-data\"");
        }
        StubBackend.Request search = searchRequest();
        assertThat(search.decodedPath()).as("the restricted index and every alias are not in the request").isEqualTo(
                "/logs-team-a-2026.10.26,logs-team-a-2026.10.27/_search");
        assertThat(search.query()).contains("expand_wildcards=open", "ignore_unavailable=true", "allow_no_indices=true");
        assertThat(search.body()).contains("\"size\":0", "\"aggs\":{\"groups\":{\"terms\":", "\"field\":\"log.level\"", "\"size\":10",
                        "\"multi_match\":{\"query\":\"payment failed\"", "\"case_insensitive\":true",
                        "{\"term\":{\"_index\":\"logs-team-a\"}}")
                .doesNotContain("script\":", "logs-team-a-hr");
    }

    @Test
    void aliasesAreNotCountedWhateverTheyAreNamedOrPointAt() {
        try (McpSyncClient client = clientInGroups(PAT, READ_GROUP, GRANT_TEAM_B)) {
            for (String prefix : new String[] {"logs-team-b-filtered", "logs-team-b-routed-index", "logs-team-b-all", "logs-team-b-leak",
                    "logs-team-b-two", "logs-team-b-stream"}) {
                McpSchema.CallToolResult result = call(client, "aggregate_logs", args(prefix));

                assertThat(result.isError()).as(prefix + ": " + text(result)).isFalse();
                assertThat(text(result)).as(prefix).contains("\"buckets\":[]");
            }
            assertThat(call(client, "aggregate_logs", args("logs-team-b")).isError()).isFalse();
        }
        assertThat(BACKEND.requests()).filteredOn(r -> r.path().endsWith("/_search"))
                .extracting(StubBackend.Request::decodedPath).containsExactly("/logs-team-b-1/_search");
    }

    @Test
    void aPrefixOutsideTheScopeOrRestrictedCountsNothingAndAsksNothing() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            String missing = text(call(client, "aggregate_logs", args("logs-team-a-missing"))).replace("logs-team-a-missing", "PREFIX");
            assertThat(missing).contains("\"buckets\":[]");
            for (String prefix : new String[] {"logs-team-a-hr", "logs-team-a-hr-1", "secret-hr-1", "logs-team-ab"}) {
                assertThat(text(call(client, "aggregate_logs", args(prefix))).replace(prefix, "PREFIX")).as(prefix).isEqualTo(missing);
            }
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.path().endsWith("/_search"));
    }

    @Test
    void wildcardsAndListsAreRejectedBeforeOpenSearch() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String pattern : new String[] {"*", "_all", "logs-team-a-*", "logs-team-a*", "logs-team-a,logs-team-b", ".security", "-x"}) {
                assertInvalidArgument(call(client, "aggregate_logs", args(pattern)));
            }
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void invalidArgumentsAreRejectedBeforeOpenSearch() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (Map.Entry<String, Object> bad : List.<Map.Entry<String, Object>>of(Map.entry("group_by", "_index"),
                    Map.entry("group_by", "message"), Map.entry("group_by", "level,service"), Map.entry("size", 51),
                    Map.entry("size", 0), Map.entry("level", "error"), Map.entry("index_pattern", "*"), Map.entry("index_pattern", "logs-team-a-*"))) {
                Map<String, Object> arguments = args("logs-team-a");
                arguments.put(bad.getKey(), bad.getValue());

                assertInvalidArgument(call(client, "aggregate_logs", arguments));
            }
            Map<String, Object> noGroup = args("logs-team-a");
            noGroup.remove("group_by");
            assertInvalidArgument(call(client, "aggregate_logs", noGroup));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void isListedAsReadOnlyWithAClosedSchema() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.Tool tool = client.listTools().tools().stream()
                    .filter(t -> t.name().equals("aggregate_logs")).findFirst().orElseThrow();

            assertThat(tool.annotations().readOnlyHint()).isTrue();
            assertThat(tool.title()).isNotBlank();
            assertThat(tool.inputSchema()).containsEntry("additionalProperties", false);
            assertThat(tool.inputSchema()).extractingByKey("required").asInstanceOf(InstanceOfAssertFactories.LIST)
                    .containsExactlyInAnyOrder("index_pattern", "group_by", "from", "to");
            assertThat(properties(tool.inputSchema()).keySet()).containsExactlyInAnyOrder("index_pattern", "group_by",
                    "text", "level", "service", "from", "to", "size");
        }
    }
}
