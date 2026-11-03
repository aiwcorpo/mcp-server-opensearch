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

    /** The stub answers five raw hits, so a page of size 5 is full. */
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
                .contains("\"sort\":[{\"@timestamp\":{\"order\":\"desc\",\"unmapped_type\":\"date\"}},{\"event.id\":{\"order\":\"asc\"}}]")
                .doesNotContain("search_after", "_id\"", "script");
    }

    @Test
    void aFullPageHasACursorThatContinuesAfterTheLastHitOfTheAnswer() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult first = call(client, "search_logs", args(5));

            assertThat(first.isError()).as(text(first)).isFalse();
            assertThat(text(first)).contains("\"items\":[", "\"truncated\":false", "\"omitted\":[]");
            Map<String, Object> next = args(5);
            next.put("cursor", nextCursor(text(first)));

            McpSchema.CallToolResult second = call(client, "search_logs", next);

            assertThat(second.isError()).as(text(second)).isFalse();
            assertThat(text(second)).contains("second page entry", "\"nextCursor\":null").doesNotContain("payment failed <script>");
        }
        assertThat(searches()).hasSize(2);
        assertThat(searches().get(1).body()).as("the position of the last raw hit, the restricted one included")
                .contains("\"search_after\":[1793091900000,\"evt-hr-2\"]");
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
            cursor = nextCursor(text(call(client, "search_logs", args(5))));
            Map<String, Object> otherText = args(5);
            otherText.put("text", "something else");
            otherText.put("cursor", cursor);
            Map<String, Object> otherRange = args(5);
            otherRange.put("to", "2026-10-27T11:00:00Z");
            otherRange.put("cursor", cursor);
            Map<String, Object> otherSize = args(4);
            otherSize.put("cursor", cursor);
            Map<String, Object> forged = args(5);
            forged.put("cursor", cursor.substring(0, cursor.length() - 2) + "AA");
            Map<String, Object> shapeless = args(5);
            shapeless.put("cursor", "not.a.cursor");

            for (Map<String, Object> arguments : List.of(otherText, otherRange, otherSize, forged, shapeless)) {
                McpSchema.CallToolResult result = call(client, "search_logs", arguments);

                assertInvalidArgument(result);
                assertThat(text(result)).contains("The cursor is not valid for this query or has expired");
            }
        }
        try (McpSyncClient other = clientAs(user -> user.subject("another-subject").username("mallory")
                .groups(List.of(READ_GROUP, GRANT_TEAM_A, GRANT_TEAM_B)), PAT, CREDENTIAL_TYPE)) {
            Map<String, Object> arguments = args(5);
            arguments.put("cursor", cursor);

            assertInvalidArgument(call(other, "search_logs", arguments));
        }
        assertThat(searches()).as("a refused cursor reaches OpenSearch never").hasSize(1);
    }

    @Test
    void aCursorDoesNotWidenTheScopeOfALaterCall() {
        String cursor;
        try (McpSyncClient client = clientWithCredential(PAT)) {
            cursor = nextCursor(text(call(client, "search_logs", args(5))));
        }
        BACKEND.reset();
        try (McpSyncClient narrower = clientInGroups(PAT, READ_GROUP)) {
            Map<String, Object> arguments = args(5);
            arguments.put("cursor", cursor);

            McpSchema.CallToolResult result = call(narrower, "search_logs", arguments);

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).contains("\"items\":[]");
        }
        assertThat(BACKEND.requests()).as("no grant, nothing queried").isEmpty();
    }
}
