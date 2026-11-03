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

    /** The scope of {@link #reader}: both granted prefixes, each as the name and as the children. */
    private static final String SCOPE_EXPRESSION = "logs-team-a,logs-team-a-*,logs-team-b,logs-team-b-*";

    private static Map<String, Object> args(String pattern) {
        Map<String, Object> args = new HashMap<>();
        args.put("index_prefix", pattern);
        args.put("text", "payment failed");
        args.put("from", "2026-10-27T08:00:00Z");
        args.put("to", "2026-10-27T10:00:00+00:00");
        args.put("size", 10);
        return args;
    }

    private static List<String> searchPaths() {
        return BACKEND.requests().stream().filter(r -> r.path().endsWith("/_search")).map(StubBackend.Request::decodedPath).toList();
    }

    @Test
    void searchesTheNamedIndicesAsTheUserAndReturnsOnlyConfiguredFieldsOfAllowedOnes() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-a"));

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result))
                    .contains("\"index\":\"logs-team-a-2026.10.27\"", "\"id\":\"Xk3n8ZIBdLrT0q1aHf2p\"",
                            "\"timestamp\":\"2026-10-27T09:15:02.123Z\"", "\"items\":[")
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
        assertThat(searchPaths()).as("names, one by one: no pattern, no alias, no restricted index, no neighbour")
                .containsExactly("/logs-team-a-2026.10.26,logs-team-a-2026.10.27/_search");
        StubBackend.Request resolve = BACKEND.requests().get(0);
        assertThat(resolve.method()).isEqualTo("GET");
        assertThat(resolve.decodedPath()).as("the only pattern is the scope of the caller").isEqualTo("/_resolve/index/" + SCOPE_EXPRESSION);
        assertThat(resolve.query()).isEqualTo("allow_no_indices=true&expand_wildcards=open&ignore_unavailable=true");
        assertThat(BACKEND.requests().stream().filter(r -> r.method().equals("GET")).map(StubBackend.Request::decodedPath))
                .as("the resolution is repeated for the hits, never reused").containsOnly("/_resolve/index/" + SCOPE_EXPRESSION);
        StubBackend.Request search = BACKEND.requests().stream().filter(r -> r.path().endsWith("/_search")).findFirst().orElseThrow();
        assertThat(search.method()).isEqualTo("POST");
        assertThat(search.query()).contains("expand_wildcards=open", "ignore_unavailable=true", "allow_no_indices=true",
                "allow_partial_search_results=false");
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
    void theTimeRangeSelectsIndicesByTheDateInTheirNames() {
        Map<String, Object> wide = args("logs-team-a");
        wide.put("from", "2026-10-26T12:00:00Z");
        Map<String, Object> hostile = args("logs-team-a");
        hostile.put("from", "9999-12-31T23:59:59Z");
        hostile.put("to", "+999999999-12-31T23:59:59Z");
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "search_logs", wide).isError()).isFalse();
            assertInvalidArgument(call(client, "search_logs", hostile));
        }
        assertThat(searchPaths()).containsExactly("/logs-team-a-2026.10.26,logs-team-a-2026.10.27/_search");
    }

    @Test
    void aRangeFarFromTheDatesInTheNamesQueriesNothing() {
        Map<String, Object> later = args("logs-team-a");
        later.put("from", "2026-10-29T08:00:00Z");
        later.put("to", "2026-10-29T10:00:00Z");
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", later);

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).contains("\"items\":[]");
        }
        assertThat(searchPaths()).isEmpty();
    }

    @Test
    void aFullIndexNameIsAPrefixOfItselfAndSearchesThatIndexAlone() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "search_logs", args("logs-team-a-2026.10.27")).isError()).isFalse();
        }
        assertThat(searchPaths()).containsExactly("/logs-team-a-2026.10.27/_search");
    }

    @Test
    void aShorterPrefixReachesOnlyWhatTheScopeCovers() {
        try (McpSyncClient client = clientInGroups(PAT, READ_GROUP, GRANT_TEAM_A)) {
            assertThat(call(client, "search_logs", args("logs")).isError()).isFalse();
            assertThat(call(client, "search_logs", args("logs-team")).isError()).isFalse();
        }
        assertThat(searchPaths()).as("team b, the neighbour and secret-hr-1 are not in the request")
                .containsOnly("/logs-team-a-2026.10.26,logs-team-a-2026.10.27/_search");
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
                    .containsExactlyInAnyOrder("index_prefix", "text", "from", "to");
            Map<String, Object> properties = properties(schema);
            assertThat(properties.keySet()).containsExactlyInAnyOrder("index_prefix", "text", "level", "service", "from", "to", "size", "cursor");
            assertThat(properties.get("index_prefix")).asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("maxLength", 255).as("the pattern comes from the contracts, not from a copy in the server")
                    .containsEntry("pattern", "^(?:[a-z0-9][a-z0-9._-]{0,253}[a-z0-9_]|[a-z0-9])$");
            assertThat(properties.get("text")).asInstanceOf(InstanceOfAssertFactories.MAP).containsEntry("maxLength", 512);
            assertThat(properties.get("size")).asInstanceOf(InstanceOfAssertFactories.MAP).containsEntry("maximum", 100);
            assertThat(properties.get("cursor")).asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("maxLength", 1024).containsEntry("pattern", "^[A-Za-z0-9_.-]{1,1024}$");
        }
    }

    @Test
    void withoutCredentialAsksToConnectTheAccountAndDoesNotCallOpenSearch() {
        try (McpSyncClient client = clientWithCredential(null)) {
            assertCredentialRequired(call(client, "search_logs", args("logs-team-a")));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void credentialRejectedByOpenSearchAsksToReconnect() {
        try (McpSyncClient client = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            assertCredentialRequired(call(client, "search_logs", args("logs-team-a")));
        }
    }

    @Test
    void theExchangedTokenGoesToOpenSearchAsBearerOnEveryRequest() {
        try (McpSyncClient client = clientWithCredential("Bearer exchanged-jwt-for-opensearch")) {
            assertThat(call(client, "search_logs", args("logs-team-a")).isError()).isFalse();
        }
        assertThat(BACKEND.authorizations()).isNotEmpty().containsOnly("Bearer exchanged-jwt-for-opensearch");
    }

    @Test
    void aCredentialTypeOutsideTheServersSetIsRefusedBeforeAnythingElse() {
        for (String type : new String[] {"service-account", "user-secret", "user-oauth", "Token-Exchange", "api-key"}) {
            try (McpSyncClient client = clientWithCredential("Bearer shared-service-token", type)) {
                McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-a"));

                assertThat(result.isError()).as(type).isTrue();
                assertThat(text(result)).as(type).contains("\"error\":\"internal_error\"").doesNotContain("shared-service-token");
            }
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void aSchemeOtherThanBearerIsNotUsed() {
        try (McpSyncClient client = clientWithCredential("ApiKey abc123")) {
            assertCredentialRequired(call(client, "search_logs", args("logs-team-a")));
        }
        try (McpSyncClient client = clientWithCredential("Basic am5vd2Fsc2tpOnB3")) {
            assertCredentialRequired(call(client, "search_logs", args("logs-team-a")));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void anAliasNamedLikeAGrantedPrefixIsNeverQueriedNorCounted() {
        try (McpSyncClient client = clientInGroups(PAT, READ_GROUP, GRANT_TEAM_A)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-a"));
            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).doesNotContain("logs-team-b-1");

            McpSchema.CallToolResult byAliasName = call(client, "search_logs", args("logs-team-a-all"));
            assertThat(byAliasName.isError()).as(text(byAliasName)).isFalse();
            assertThat(text(byAliasName)).contains("\"items\":[]");
        }
        assertThat(searchPaths()).as("the alias that points at team b appears in no request")
                .containsExactly("/logs-team-a-2026.10.26,logs-team-a-2026.10.27/_search");
    }

    @Test
    void anAliasMatchingThePrefixPatternButLeadingOutsideIsIgnored() {
        try (McpSyncClient client = clientInGroups(PAT, READ_GROUP, GRANT_TEAM_B)) {
            for (String prefix : new String[] {"logs-team-b-all", "logs-team-b-leak", "logs-team-b-hidden-target", "logs-team-b-two",
                    "logs-team-b-filtered"}) {
                McpSchema.CallToolResult result = call(client, "search_logs", args(prefix));

                assertThat(result.isError()).as(prefix + ": " + text(result)).isFalse();
                assertThat(text(result)).as(prefix).contains("\"items\":[]").doesNotContain("secret-hr", "logs-team-a");
            }
            assertThat(call(client, "search_logs", args("logs-team-b")).isError()).isFalse();
        }
        assertThat(searchPaths()).as("only the concrete index of team b; the hidden index named like the prefix is left out")
                .containsExactly("/logs-team-b-1/_search");
    }

    @Test
    void aDataStreamAndItsBackingIndicesAreNeverQueried() {
        try (McpSyncClient client = clientInGroups(PAT, READ_GROUP, GRANT_TEAM_B)) {
            McpSchema.CallToolResult byName = call(client, "search_logs", args("logs-team-b-stream"));
            assertThat(byName.isError()).as(text(byName)).isFalse();
            assertThat(text(byName)).contains("\"items\":[]");
            assertThat(call(client, "search_logs", args("logs-team-b")).isError()).isFalse();
        }
        assertThat(searchPaths()).containsExactly("/logs-team-b-1/_search");
        assertThat(BACKEND.requests()).filteredOn(r -> r.path().endsWith("/_search"))
                .noneMatch(r -> r.decodedPath().contains("stream") || r.decodedPath().contains(".ds-"));
    }

    @Test
    void anIndexFromAnotherTeamAndAMissingOneGiveTheSameEmptyResult() {
        try (McpSyncClient client = clientInGroups(PAT, READ_GROUP, GRANT_TEAM_A)) {
            String missing = text(call(client, "search_logs", args("logs-team-a-nothing"))).replace("logs-team-a-nothing", "PREFIX");
            assertThat(missing).contains("\"items\":[]");
            for (String outside : new String[] {"logs-team-b", "logs-team-b-1", "secret-hr-1", "secret", "logs-team-ab", "logs-team-ab-1",
                    "logs-team-a-restricted", "logs-team-a-hidden", "logs-team-a-hr-1"}) {
                assertThat(text(call(client, "search_logs", args(outside))).replace(outside, "PREFIX")).as(outside).isEqualTo(missing);
            }
        }
        assertThat(BACKEND.requests()).as("nothing reached OpenSearch but the resolution of the caller's own scope")
                .allMatch(r -> r.method().equals("GET") && r.decodedPath().equals("/_resolve/index/logs-team-a,logs-team-a-*"));
    }

    @Test
    void anEmptyClusterIsAnEmptyResult() {
        try (McpSyncClient client = clientWithCredential(StubBackend.EMPTY_CLUSTER_CREDENTIAL)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-a"));

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).contains("\"items\":[]");
        }
        assertThat(BACKEND.requests()).hasSize(1);
    }

    @Test
    void indexPrefixsBeyondOnePlainPrefixAreRejectedBeforeOpenSearch() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String pattern : new String[] {"*", "_all", "-logs-team-a-*", ".security", ".ds-logs-team-a-stream-000001", ".",
                    "logs-team-a-*", "logs-team-a*", "logs-team-a-*,secret-*", "logs-team-a,logs-team-b", ",", "logs-team-a,", ",logs-team-a",
                    "logs-team-a-2026,secret-hr-1", "remote:logs-team-a-*", "<logs-team-a-{now/d}>", "logs-team-a,-logs-team-a-hr-1",
                    "logs-*-a", "logs-team-a-**", "LOGS-TEAM-A", "logs-team-a-", "logs-team-a.", "logs team a", "logs-team-a/", "logs-team-a?", "_logs", "",
                    "a".repeat(256)}) {
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
    void argumentsBreakingTheSchemaAreRejectedByTheStarterBeforeOpenSearch() {
        Map<String, Object> extra = args("logs-team-a");
        extra.put("aggs", Map.of("by_index", Map.of("terms", Map.of("field", "_index"))));
        Map<String, Object> missingQuery = args("logs-team-a");
        missingQuery.remove("text");
        Map<String, Object> nullQuery = args("logs-team-a");
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
        Map<String, Object> arguments = args("logs-team-a");
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
        Map<String, Object> arguments = args("logs-team-a");
        arguments.remove("size");
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(call(client, "search_logs", arguments).isError()).isFalse();
        }
        assertThat(BACKEND.requests().stream().filter(r -> r.path().endsWith("/_search")).findFirst().orElseThrow().body())
                .contains("\"size\":20");
    }

    @Test
    void theCredentialAndTheBackendErrorNeverAppearInTheResultOrTheLog(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(StubBackend.BOOM_CREDENTIAL)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-a"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain("secret-boom-token");
        }
        assertThat(output.getAll()).contains("\"tool\":\"search_logs\"").doesNotContain("secret-boom-token")
                .as("the backend's error body stays out of the log").doesNotContain("boom\"");
    }

    @Test
    void anUnreadableBodyStaysOutOfTheResultAndTheLog(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(StubBackend.BADJSON_CREDENTIAL)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-a"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain(StubBackend.BODY_MARKER);
        }
        assertThat(output.getAll()).contains("BackendResponseException").doesNotContain(StubBackend.BODY_MARKER);
    }

    @Test
    void aRedirectIsNotFollowedAndNotReadAsMissing() {
        try (McpSyncClient client = clientWithCredential(StubBackend.REDIRECT_CREDENTIAL)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args("logs-team-a"));

            assertThat(text(result)).contains("\"error\":\"internal_error\"");
        }
        assertThat(BACKEND.elsewhereHits()).as("the credential must not follow the Location").isEmpty();
    }

    private static Map<String, Object> with(String name, Object value) {
        Map<String, Object> arguments = args("logs-team-a");
        arguments.put(name, value);
        return arguments;
    }
}
