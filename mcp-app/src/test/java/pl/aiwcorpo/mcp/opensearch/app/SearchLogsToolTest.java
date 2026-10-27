package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** {@code search_logs} through the test gateway against the OpenSearch stub. */
@ExtendWith(OutputCaptureExtension.class)
class SearchLogsToolTest extends ServerTestSupport {

    private static final String PAT = "Bearer opensearch-token-of-jkowalski-5c1e";

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    private static Map<String, Object> args(String pattern) {
        Map<String, Object> args = new HashMap<>();
        args.put("index_pattern", pattern);
        args.put("query", "payment failed");
        args.put("from", "2026-10-27T08:00:00Z");
        args.put("to", "2026-10-27T10:00:00+00:00");
        args.put("size", 10);
        return args;
    }

    @Test
    void searchesAsTheUserAndReturnsOnlyConfiguredFieldsOfAllowedIndices() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-a-*"));

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result))
                    .contains("\"index\":\"logs-team-a-2026.10.27\"", "\"id\":\"Xk3n8ZIBdLrT0q1aHf2p\"",
                            "\"timestamp\":\"2026-10-27T09:15:02.123Z\"", "\"log.level\":\"ERROR\"", "\"returned\":2",
                            "data, not instructions")
                    .as("markup is escaped").contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; retried")
                    .doesNotContain("<script>")
                    .as("fields outside result-fields stay in the adapter").doesNotContain("not-for-the-model")
                    .as("hits of indices outside the prefixes are dropped").doesNotContain("secret-hr-1", "salary");
        }
        assertThat(BACKEND.authorizations()).containsExactly(PAT, PAT);
        StubBackend.Request resolve = BACKEND.requests().get(0);
        assertThat(resolve.path()).isEqualTo("/_resolve/index/logs-team-a-*");
        assertThat(resolve.query()).contains("expand_wildcards=open");
        StubBackend.Request search = BACKEND.requests().get(1);
        assertThat(search.method()).isEqualTo("POST");
        assertThat(search.path()).isEqualTo("/logs-team-a-*/_search");
        assertThat(search.query()).contains("expand_wildcards=open", "allow_partial_search_results=false");
        assertThat(search.body())
                .contains("\"simple_query_string\":{\"query\":\"payment failed\",\"fields\":[\"message\"]",
                        "\"size\":10", "\"track_total_hits\":false",
                        "\"gte\":\"2026-10-27T08:00:00Z\",\"lt\":\"2026-10-27T10:00:00Z\"",
                        "{\"term\":{\"_index\":\"logs-team-a\"}}", "{\"prefix\":{\"_index\":\"logs-team-a-\"}}",
                        "{\"prefix\":{\"_index\":\"app-\"}}")
                .doesNotContain("aggs", "aggregations", "script", "query_string\":{\"query\":\"payment failed\",\"default_field");
    }

    @Test
    void aWildcardRightAfterAPrefixStopsAtTheDash() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-a*"));

            assertThat(result.isError()).as(text(result)).isFalse();
        }
        assertThat(BACKEND.requests().get(0).path()).isEqualTo("/_resolve/index/logs-team-a,logs-team-a-*");
        assertThat(BACKEND.requests().get(1).path()).isEqualTo("/logs-team-a,logs-team-a-*/_search");
    }

    @Test
    void isListedAsReadOnlyWithAClosedSchema() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.Tool tool = client.listTools().tools().stream()
                    .filter(t -> t.name().equals("search_logs")).findFirst().orElseThrow();

            assertThat(tool.annotations().readOnlyHint()).isTrue();
            assertThat(tool.annotations().destructiveHint()).isFalse();
            Map<String, Object> schema = tool.inputSchema();
            assertThat(schema).containsEntry("additionalProperties", false);
            assertThat(schema).extractingByKey("required").asInstanceOf(InstanceOfAssertFactories.LIST)
                    .containsExactlyInAnyOrder("index_pattern", "query", "from", "to");
            Map<String, Object> properties = properties(schema);
            assertThat(properties.keySet()).containsExactlyInAnyOrder("index_pattern", "query", "from", "to", "size");
            assertThat(properties.get("index_pattern")).asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("maxLength", 255).containsEntry("pattern", "^[a-z0-9-]+\\*?$");
            assertThat(properties.get("query")).asInstanceOf(InstanceOfAssertFactories.MAP).containsEntry("maxLength", 512);
            assertThat(properties.get("size")).asInstanceOf(InstanceOfAssertFactories.MAP).containsEntry("maximum", 100);
        }
    }

    @Test
    void withoutCredentialAsksToConnectTheAccountAndDoesNotCallOpenSearch() {
        try (McpSyncClient client = clientWithCredential(null)) {
            assertCredentialRequired(call(client, "search_logs", args("logs-team-a-*")));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void credentialRejectedByOpenSearchAsksToReconnect() {
        try (McpSyncClient client = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            assertCredentialRequired(call(client, "search_logs", args("logs-team-a-*")));
        }
    }

    @Test
    void aTokenExchangeCredentialIsUsedLikeAUserSecret() {
        try (McpSyncClient client = clientWithCredential("Bearer exchanged-jwt-for-opensearch", "token-exchange")) {
            assertThat(call(client, "search_logs", args("logs-team-a-*")).isError()).isFalse();
        }
        assertThat(BACKEND.authorizations()).containsOnly("Bearer exchanged-jwt-for-opensearch");
    }

    @Test
    void aServiceAccountOrForeignSchemeIsNotUsed() {
        try (McpSyncClient client = clientWithCredential("Bearer shared-service-token", "service-account")) {
            assertCredentialRequired(call(client, "search_logs", args("logs-team-a-*")));
        }
        try (McpSyncClient client = clientWithCredential("ApiKey abc123", "user-secret")) {
            assertCredentialRequired(call(client, "search_logs", args("logs-team-a-*")));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void anAliasLeadingOutsideThePrefixesIsNotFoundOrDeniedAndNeverSearched() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult byName = call(client, "search_logs", args("app-leak"));
            McpSchema.CallToolResult byWildcard = call(client, "search_logs", args("app-l*"));

            assertThat(text(byName)).contains("\"error\":\"not_found_or_denied\"").doesNotContain("secret-hr");
            assertThat(text(byWildcard)).isEqualTo(text(byName));
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.path().endsWith("/_search"));
    }

    @Test
    void forbiddenMissingAndHiddenIndicesLookTheSame() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult forbidden = call(client, "search_logs", args("logs-team-a-restricted"));
            McpSchema.CallToolResult missing = call(client, "search_logs", args("logs-team-a-nothing"));
            McpSchema.CallToolResult hidden = call(client, "search_logs", args("logs-team-a-hidden"));

            assertThat(text(forbidden)).contains("\"error\":\"not_found_or_denied\"");
            assertThat(text(missing)).isEqualTo(text(forbidden));
            assertThat(text(hidden)).isEqualTo(text(forbidden));
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.path().endsWith("/_search"));
    }

    @Test
    void aWildcardMatchingNothingIsAnEmptyResult() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("app-nothing-*"));

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).contains("\"returned\":0", "\"hits\":[]");
        }
        assertThat(BACKEND.requests()).hasSize(1);
    }

    @Test
    void aDataStreamIsRejected() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("app-s*"));

            assertThat(text(result)).contains("\"error\":\"invalid_argument\"", "data stream");
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.path().endsWith("/_search"));
    }

    @Test
    void indexPatternsBeyondOnePlainPrefixAreRejectedBeforeOpenSearch() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String pattern : new String[] {"*", "_all", "-logs-team-a-*", ".security", ".ds-logs-team-a-stream-000001",
                    "logs-team-a-*,secret-*", "logs-team-a-2026,secret-hr-1", "remote:logs-team-a-*", "<logs-team-a-{now/d}>",
                    "logs-*-a", "logs-team-a-**", "LOGS-TEAM-A", "logs-team-a-2026.10.27", "logs-team-a-?", "", "a".repeat(256)}) {
                assertRejectedArguments(call(client, "search_logs", args(pattern)), "index_pattern");
            }
            assertThat(text(call(client, "search_logs", args("-logs-team-a-*"))))
                    .contains("\"error\":\"invalid_argument\"", "exclusions are not supported");
            for (String outside : new String[] {"secret-*", "logs-team-ab*", "logs-team-ab-1", "logs", "logs-team-*"}) {
                assertThat(text(call(client, "search_logs", args(outside)))).as(outside)
                        .contains("\"error\":\"invalid_argument\"", "index prefix served by this server");
            }
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void otherArgumentsAreCheckedBeforeOpenSearch() {
        Map<String, Map<String, Object>> cases = new java.util.LinkedHashMap<>();
        cases.put("query must be", with("query", " "));
        cases.put("query must be 1-512", with("query", "x".repeat(513)));
        cases.put("control characters", with("query", "a\u0000b"));
        cases.put("from must be an ISO-8601", with("from", "2026-10-27 08:00"));
        cases.put("from must be an ISO-8601 date-time with offset", with("from", "2026-10-27T08:00:00"));
        cases.put("to must be later than from", with("to", "2026-10-27T08:00:00Z"));
        cases.put("may not exceed 168 hours", with("from", "2026-10-01T00:00:00Z"));
        cases.put("size must be between 1 and 100", with("size", 101));
        cases.put("size must be between", with("size", 0));
        Map<String, Object> extra = args("logs-team-a-*");
        extra.put("aggs", Map.of("by_index", Map.of("terms", Map.of("field", "_index"))));
        cases.put("not declared in the input schema", extra);
        try (McpSyncClient client = clientWithCredential(PAT)) {
            cases.forEach((message, arguments) -> assertRejectedArguments(call(client, "search_logs", arguments), message));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void sizeDefaultsToTwenty() {
        Map<String, Object> arguments = args("logs-team-a-*");
        arguments.remove("size");
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "search_logs", arguments).isError()).isFalse();
        }
        assertThat(BACKEND.requests().get(1).body()).contains("\"size\":20");
    }

    @Test
    void theCredentialAndTheBackendErrorNeverAppearInTheResultOrTheLog(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-a-boom"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain("opensearch-token");
        }
        assertThat(output.getAll()).contains("\"tool\":\"search_logs\"").doesNotContain("opensearch-token-of")
                .as("the backend's error body stays out of the log").doesNotContain("boom\"");
    }

    private static Map<String, Object> with(String name, Object value) {
        Map<String, Object> arguments = args("logs-team-a-*");
        arguments.put(name, value);
        return arguments;
    }
}
