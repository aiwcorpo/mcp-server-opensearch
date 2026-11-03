package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code search_logs} paging with {@code search_after}: the sort, the cursor and what a cursor is bound to. */
class SearchLogsPagingTest extends ServerTestSupport {

    private static final String PAT = "Bearer opensearch-token-of-jkowalski-5c1e";

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    /** The stub answers five raw hits whatever the size; two of them are the caller's, in two readable indices. */
    private static Map<String, Object> args(int size) {
        Map<String, Object> args = new HashMap<>();
        args.put("index_prefix", "logs-team-a");
        args.put("text", "payment failed");
        args.put("from", "2026-10-27T08:00:00Z");
        args.put("to", "2026-10-27T10:00:00Z");
        args.put("size", size);
        return args;
    }

    private static List<StubBackend.Request> searches() {
        return BACKEND.requests().stream().filter(r -> r.path().endsWith("/_search")).toList();
    }

    private static String nextCursor(String result) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"nextCursor\":\"([A-Za-z0-9_.-]+)\"").matcher(result);
        assertThat(matcher.find()).as(result).isTrue();
        return matcher.group(1);
    }

    @Test
    void sortsByTimeThenByTheConfiguredUniqueFieldAndNeverByIdOrScript() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            call(client, "search_logs", args(10));
        }
        assertThat(searches().getFirst().body())
                .contains("\"sort\":[{\"@timestamp\":{\"order\":\"desc\",\"unmapped_type\":\"date\"}},{\"event.id\":{\"order\":\"asc\",\"unmapped_type\":\"keyword\"}}]")
                .doesNotContain("search_after", "_id\"", "script");
    }

    @Test
    void aFullPageHasACursorThatContinuesAfterTheLastHitOfTheAnswer() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult first = call(client, "search_logs", args(2));

            assertThat(first.isError()).as(text(first)).isFalse();
            assertThat(text(first)).contains("\"items\":[", "\"truncated\":false", "\"omitted\":[]");
            Map<String, Object> next = args(2);
            next.put("cursor", nextCursor(text(first)));

            McpSchema.CallToolResult second = call(client, "search_logs", next);

            assertThat(second.isError()).as(text(second)).isFalse();
            assertThat(text(second)).contains("second page entry", "\"nextCursor\":null").doesNotContain("payment failed <script>");
        }
        assertThat(searches()).hasSize(2);
        assertThat(searches().get(1).body()).as("the position of the last hit the caller got, not of the restricted one the answer ended with")
                .contains("\"search_after\":[1793059199000,\"evt-Ab0n8ZIBdLrT0q1aHf00\"]")
                .doesNotContain("evt-hr-2", "1793091900000");
    }

    @Test
    void aShortPageHasNoCursor() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args(10));

            assertThat(text(result)).contains("\"nextCursor\":null");
        }
    }

    @Test
    void aCursorBelongsToItsQueryItsCallerAndItsTool() {
        String cursor;
        try (McpSyncClient client = clientWithCredential(PAT)) {
            cursor = nextCursor(text(call(client, "search_logs", args(2))));
            Map<String, Object> otherText = args(2);
            otherText.put("text", "something else");
            otherText.put("cursor", cursor);
            Map<String, Object> otherRange = args(2);
            otherRange.put("to", "2026-10-27T11:00:00Z");
            otherRange.put("cursor", cursor);
            Map<String, Object> otherSize = args(3);
            otherSize.put("cursor", cursor);
            Map<String, Object> forged = args(2);
            forged.put("cursor", cursor.substring(0, cursor.length() - 2) + "AA");
            Map<String, Object> shapeless = args(2);
            shapeless.put("cursor", "not.a.cursor");

            for (Map<String, Object> arguments : List.of(otherText, otherRange, otherSize, forged, shapeless)) {
                McpSchema.CallToolResult result = call(client, "search_logs", arguments);

                assertInvalidArgument(result);
                assertThat(text(result)).contains("The cursor is not valid for this query or has expired");
            }
        }
        try (McpSyncClient other = clientAs(user -> user.subject("another-subject").username("mallory")
                .groups(List.of(READ_GROUP, GRANT_TEAM_A, GRANT_TEAM_B)), PAT, CREDENTIAL_TYPE)) {
            Map<String, Object> arguments = args(2);
            arguments.put("cursor", cursor);

            assertInvalidArgument(call(other, "search_logs", arguments));
        }
        assertThat(searches()).as("a refused cursor reaches OpenSearch never").hasSize(1);
    }

    @Test
    void aCursorDoesNotWidenTheScopeOfALaterCall() {
        String cursor;
        try (McpSyncClient client = clientWithCredential(PAT)) {
            cursor = nextCursor(text(call(client, "search_logs", args(2))));
        }
        BACKEND.reset();
        try (McpSyncClient narrower = clientInGroups(PAT, READ_GROUP)) {
            Map<String, Object> arguments = args(2);
            arguments.put("cursor", cursor);

            McpSchema.CallToolResult result = call(narrower, "search_logs", arguments);

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).contains("\"items\":[]");
        }
        assertThat(BACKEND.requests()).as("no grant, nothing queried").isEmpty();
    }

    @Test
    void aCursorHoldsNoSortValueNorIdOfAHitTheCallerMayNotSee() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args(2));
            String cursor = nextCursor(text(result));

            assertThat(cursor).as("the position is encrypted").startsWith("c2.");
            for (String hidden : List.of("evt-hr-2", "hr-2", "1793091900000", "salary", "evt-hr-1")) {
                assertThat(cursor).doesNotContain(hidden);
                assertThat(cursor).doesNotContain(java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(hidden.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            assertThat(text(result)).doesNotContain("logs-team-a-hr-1", "salary list", "neighbour of the prefix");
            Map<String, Object> next = args(2);
            next.put("cursor", cursor);
            call(client, "search_logs", next);
        }
        assertThat(searches().get(1).body()).as("the next page continues after the last hit the caller got")
                .contains("\"search_after\":[1793059199000,\"evt-Ab0n8ZIBdLrT0q1aHf00\"]")
                .doesNotContain("evt-hr-2", "1793091900000");
    }

    @Test
    void aPageCutByTheSizeContinuesAfterTheLastHitItReturned() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", args(1));
            Map<String, Object> next = args(1);
            next.put("cursor", nextCursor(text(result)));
            call(client, "search_logs", next);

            assertThat(text(result)).contains("Xk3n8ZIBdLrT0q1aHf2p").doesNotContain("Ab0n8ZIBdLrT0q1aHf00");
        }
        assertThat(searches().get(1).body()).contains("\"search_after\":[1793092502123,\"evt-Xk3n8ZIBdLrT0q1aHf2p\"]");
    }

    @Test
    void aPageThatTheFirstReadLeftEmptyIsFilledByTheNextAndCutToTheSize() {
        Map<String, Object> arguments = args(1);
        arguments.put("text", "all-hidden-first");
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", arguments);

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).contains("fill-1").doesNotContain("fill-2", "restricted note");
            assertThat(cursorLeaksNothing(nextCursor(text(result)), "evt-h5", "evt-h1")).isTrue();
        }
        assertThat(searches()).hasSize(2);
        assertThat(searches().get(1).body()).as("the second read goes on after the last raw hit of the first")
                .contains("\"search_after\":[");
        Map<String, Object> next = new HashMap<>(arguments);
        try (McpSyncClient client = clientWithCredential(PAT)) {
            next.put("cursor", nextCursor(text(call(client, "search_logs", arguments))));
            call(client, "search_logs", next);
        }
        assertThat(searches().getLast().body()).as("the cursor continues after the last hit the caller got")
                .contains("evt-fill-1").doesNotContain("evt-h");
    }

    @Test
    void whenFiveReadsFindNothingTheCallerGetsAnEmptyPageAndTheLastPosition() {
        Map<String, Object> arguments = args(2);
        arguments.put("text", "all-hidden-always");
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "search_logs", arguments);

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).contains("\"items\":[]", "\"nextCursor\":\"").doesNotContain("restricted note", "logs-team-a-hr-1");
        }
        assertThat(searches()).as("MAX_READS reads, no more").hasSize(5);
    }

    @Test
    void theCallCarriesTheTimeLimitsBelowTheReadTimeout() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            call(client, "search_logs", args(2));
            call(client, "count_logs", Map.of("index_prefix", "logs-team-a", "from", "2026-10-27T08:00:00Z", "to", "2026-10-27T10:00:00Z"));
        }
        assertThat(searches()).hasSize(2).allSatisfy(request -> {
            assertThat(request.query()).contains("cancel_after_time_interval=900ms");
            assertThat(request.body()).contains("\"timeout\":\"800ms\"");
        });
        assertThat(searches().getFirst().body()).contains("{\"event.id\":{\"order\":\"asc\",\"unmapped_type\":\"keyword\"}}");
    }

    private static boolean cursorLeaksNothing(String cursor, String... hidden) {
        return java.util.Arrays.stream(hidden).noneMatch(value -> cursor.contains(value) || cursor.contains(
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
}
