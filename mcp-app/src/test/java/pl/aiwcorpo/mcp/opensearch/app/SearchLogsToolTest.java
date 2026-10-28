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
        args.put("text", "payment failed");
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
                            "\"timestamp\":\"2026-10-27T09:15:02.123Z\"", "\"hits\":[")
                    .as("free text is marked as untrusted data").contains("{\"name\":\"log.level\",\"path\":null,"
                            + "\"value\":{\"kind\":\"untrusted-data\",\"format\":\"text/plain\",\"text\":\"ERROR\","
                            + "\"truncated\":false}}")
                    .as("text is passed on as it is, not escaped").contains("<script>alert(1)</script> & retried")
                    .doesNotContain("&lt;", "&amp;")
                    .as("a hit of an index the policy refuses (tagged restricted) is removed after the tool ran")
                    .doesNotContain("logs-team-a-hr-1", "restricted payroll", "\"returned\"")
                    .as("fields outside result-fields stay in the adapter").doesNotContain("not-for-the-model")
                    .as("hits of indices outside the prefixes are dropped").doesNotContain("secret-hr-1", "salary", "logs-team-ab-1", "neighbour");
        }
        assertThat(BACKEND.requests().stream().map(StubBackend.Request::path)).containsExactly(
                "/_resolve/index/logs-team-a-%2A", "/logs-team-a-%2A/_search", "/_resolve/index/logs-team-a-2026.10.27",
                "/_resolve/index/logs-team-a-2026.10.26", "/_resolve/index/logs-team-a-hr-1");
        StubBackend.Request resolve = BACKEND.requests().get(0);
        assertThat(resolve.path()).isEqualTo("/_resolve/index/logs-team-a-%2A");
        assertThat(resolve.query()).contains("expand_wildcards=open");
        StubBackend.Request search = BACKEND.requests().get(1);
        assertThat(search.method()).isEqualTo("POST");
        assertThat(search.path()).isEqualTo("/logs-team-a-%2A/_search");
        assertThat(search.query()).contains("expand_wildcards=open", "allow_partial_search_results=false");
        assertThat(search.body())
                .contains("\"multi_match\":{\"query\":\"payment failed\",\"fields\":[\"message\"],\"type\":\"best_fields\","
                                + "\"operator\":\"and\",\"lenient\":true}",
                        "\"size\":10", "\"track_total_hits\":false",
                        "\"gte\":\"2026-10-27T08:00:00Z\",\"lt\":\"2026-10-27T10:00:00Z\"",
                        "{\"term\":{\"_index\":\"logs-team-a\"}}", "{\"prefix\":{\"_index\":\"logs-team-a-\"}}",
                        "{\"prefix\":{\"_index\":\"logs-team-b-\"}}")
                .doesNotContain("aggs", "aggregations", "script", "query_string", "simple_query_string");
    }

    @Test
    void aWildcardRightAfterAPrefixStopsAtTheDash() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-a*"));

            assertThat(result.isError()).as(text(result)).isFalse();
        }
        // The comma is written by the server and sent encoded, the wildcard too; this is what OpenSearch decodes
        // before it splits the expression.
        assertThat(BACKEND.requests().get(0).path()).isEqualTo("/_resolve/index/logs-team-a%2Clogs-team-a-%2A");
        assertThat(BACKEND.requests().get(1).path()).isEqualTo("/logs-team-a%2Clogs-team-a-%2A/_search");
        assertThat(BACKEND.requests().get(1).decodedPath()).isEqualTo("/logs-team-a,logs-team-a-*/_search");
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
                    .containsExactlyInAnyOrder("index_pattern", "text", "from", "to");
            Map<String, Object> properties = properties(schema);
            assertThat(properties.keySet()).containsExactlyInAnyOrder("index_pattern", "text", "level", "service", "from", "to", "size");
            assertThat(properties.get("index_pattern")).asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("maxLength", 255).containsEntry("pattern", "^[a-z0-9-]+\\*?$");
            assertThat(properties.get("text")).asInstanceOf(InstanceOfAssertFactories.MAP).containsEntry("maxLength", 512);
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
            McpSchema.CallToolResult byName = call(client, "search_logs", args("logs-team-b-leak"));
            McpSchema.CallToolResult byWildcard = call(client, "search_logs", args("logs-team-b-l*"));
            McpSchema.CallToolResult toHiddenIndex = call(client, "search_logs", args("logs-team-b-hidden-target"));

            assertThat(text(byName)).contains("\"error\":\"not_found_or_denied\"").doesNotContain("secret-hr");
            assertThat(text(byWildcard)).isEqualTo(text(byName));
            assertThat(text(toHiddenIndex)).as("an in-scope alias must not lead to a hidden index")
                    .isEqualTo(text(byName));
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
            McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-b-nothing-*"));

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).contains("\"hits\":[]");
        }
        assertThat(BACKEND.requests()).hasSize(1);
    }

    @Test
    void aDataStreamIsRejected() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-b-s*"));

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
                McpSchema.CallToolResult result = call(client, "search_logs", args(pattern));

                assertInvalidArgument(result);
                if (pattern.length() > 3) {
                    assertThat(text(result)).as("the value is not echoed").doesNotContain(pattern);
                }
            }
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aWellFormedPatternOutsideTheScopeLooksLikeAMissingIndex() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            String missing = text(call(client, "search_logs", args("logs-team-a-nothing")));
            assertThat(missing).contains("\"error\":\"not_found_or_denied\"");
            for (String outside : new String[] {"secret-*", "logs-team-ab*", "logs-team-ab-1", "logs", "logs-team-*"}) {
                assertThat(text(call(client, "search_logs", args(outside)))).as(outside).isEqualTo(missing);
            }
        }
        assertThat(BACKEND.requests()).as("only the in-scope name reached OpenSearch (resolve)")
                .allMatch(r -> r.decodedPath().equals("/_resolve/index/logs-team-a-nothing"));
    }

    @Test
    void argumentsBreakingTheSchemaAreRejectedByTheStarterBeforeOpenSearch() {
        Map<String, Object> extra = args("logs-team-a-*");
        extra.put("aggs", Map.of("by_index", Map.of("terms", Map.of("field", "_index"))));
        Map<String, Object> missingQuery = args("logs-team-a-*");
        missingQuery.remove("text");
        Map<String, Object> nullQuery = args("logs-team-a-*");
        nullQuery.put("text", null);
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (Map<String, Object> arguments : java.util.List.of(extra, missingQuery, nullQuery, with("text", "x".repeat(513)),
                    with("text", 42), with("level", "info"), with("level", "ERROR OR 1"), with("service", "a b"), with("service", "x\" OR"), with("from", "2026-10-27 08:00"), with("from", "2026-10-27T08:00:00"),
                    with("size", 101), with("size", 0), with("size", "10"), with("size", 1.5))) {
                McpSchema.CallToolResult result = call(client, "search_logs", arguments);

                assertInvalidArgument(result);
                assertThat(text(result)).doesNotContain("aggs", "by_index", "xxxxxxxx");
            }
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void argumentsThatPassTheSchemaAreCheckedByTheToolBeforeOpenSearch() {
        Map<String, Map<String, Object>> cases = new java.util.LinkedHashMap<>();
        cases.put("text must be", with("text", " "));
        cases.put("control characters", with("text", "a\u0000b"));
        cases.put("from must be an ISO-8601 date-time with offset", with("from", "2026-10-27 08:00:00Z+01"));
        cases.put("to must be later than from", with("to", "2026-10-27T08:00:00Z"));
        cases.put("may not exceed 168 hours", with("from", "2026-10-01T00:00:00Z"));
        cases.put("index_pattern must not start with '-'", with("index_pattern", "-logs-team-a-*"));
        try (McpSyncClient client = clientWithCredential(PAT)) {
            cases.forEach((message, arguments) -> {
                McpSchema.CallToolResult result = call(client, "search_logs", arguments);

                assertInvalidArgument(result);
                assertThat(text(result)).contains(message);
            });
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void anUnknownArgumentNameCannotForgeALogLine(CapturedOutput output) {
        String forged = "{\"ts\":\"2099-01-01T00:00:00Z\",\"event\":\"tool_call\",\"outcome\":\"FORGED\"}";
        Map<String, Object> arguments = args("logs-team-a-*");
        arguments.put("x\n" + forged + "\nY", "v");
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", arguments);

            assertInvalidArgument(result);
            assertThat(text(result)).doesNotContain("FORGED");
        }
        assertThat(output.getAll()).as("the name from the client is not logged at all").doesNotContain("FORGED");
        assertThat(output.getAll().lines()).noneMatch(line -> line.startsWith("{\"ts\":\"2099"));
        assertThat(output.getAll()).as("the refused call is still audited (C8)").contains("\"tool\":\"search_logs\"");
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

    @Test
    void anUnreadableBodyStaysOutOfTheResultAndTheLog(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-a-badjson"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain(StubBackend.BODY_MARKER);
        }
        assertThat(output.getAll()).contains("BackendResponseException").doesNotContain(StubBackend.BODY_MARKER);
    }

    @Test
    void aRedirectIsNotFollowedAndNotReadAsMissing() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-a-302"));

            assertThat(text(result)).contains("\"error\":\"internal_error\"");
        }
        assertThat(BACKEND.elsewhereHits()).as("the credential must not follow the Location").isEmpty();
    }

    private static Map<String, Object> with(String name, Object value) {
        Map<String, Object> arguments = args("logs-team-a-*");
        arguments.put(name, value);
        return arguments;
    }
}
