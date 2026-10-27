package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpenSearchPort;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;

/** {@code get_document} through the test gateway against the OpenSearch stub. */
@ExtendWith(OutputCaptureExtension.class)
class GetDocumentToolTest extends ServerTestSupport {

    private static final String PAT = "Bearer opensearch-token-of-jkowalski-5c1e";
    private static final String DOC_ID = "Xk3n8ZIBdLrT0q1aHf2p";

    @Autowired
    OpenSearchPort openSearchPort;

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void readsTheDocumentAsTheUserWithOnlyConfiguredFields() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_document",
                    Map.of("index", "logs-team-a-2026.10.27", "id", DOC_ID));

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result))
                    .contains("\"index\":\"logs-team-a-2026.10.27\"", "\"id\":\"" + DOC_ID + "\"",
                            "\"service.name\":{\"kind\":\"untrusted-data\",\"format\":\"text/plain\",\"text\":\"billing\","
                                    + "\"truncated\":false}",
                            "&lt;b&gt;payment&lt;/b&gt; failed")
                    .doesNotContain("<b>", "not-for-the-model", "_seq_no");
        }
        assertThat(BACKEND.authorizations()).containsExactly(PAT, PAT);
        StubBackend.Request read = BACKEND.requests().get(1);
        assertThat(read.path()).isEqualTo("/logs-team-a-2026.10.27/_doc/" + DOC_ID);
        assertThat(read.query()).isEqualTo("_source_includes=%40timestamp%2Cmessage%2Clog.level%2Cservice.name%2Chost.name");
    }

    @Test
    void theIdIsEncodedOnTheWireAsOnePathSegment() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_document",
                    Map.of("index", "logs-team-a-2026.10.27", "id", "a:b@c+d=e"));

            assertThat(text(result)).contains("\"error\":\"not_found_or_denied\"");
        }
        assertThat(BACKEND.requests().get(1).path()).isEqualTo("/logs-team-a-2026.10.27/_doc/a%3Ab%40c%2Bd%3De");
        assertThat(BACKEND.requests().get(1).decodedPath()).isEqualTo("/logs-team-a-2026.10.27/_doc/a:b@c+d=e");
    }

    @Test
    void anAliasIsReadThroughItsSingleConcreteIndex() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_document", Map.of("index", "logs-team-a-all", "id", DOC_ID));

            assertThat(result.isError()).as(text(result)).isFalse();
        }
        assertThat(BACKEND.requests().stream().map(StubBackend.Request::path)).containsExactly(
                "/_resolve/index/logs-team-a-all", "/_resolve/index/logs-team-a-2026.10.27", "/_alias/logs-team-a-all",
                "/logs-team-a-2026.10.27/_doc/" + DOC_ID);
    }

    @Test
    void anAliasWithAFilterOrRoutingIsNotRead() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_document", Map.of("index", "app-filtered", "id", DOC_ID));

            assertThat(text(result)).contains("\"error\":\"not_found_or_denied\"");
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.path().contains("/_doc/"));
    }

    @Test
    void anAliasOfSeveralIndicesOrADataStreamIsNotRead() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(text(call(client, "get_document", Map.of("index", "app-two", "id", DOC_ID))))
                    .contains("\"error\":\"invalid_argument\"", "alias of several indices");
            assertThat(text(call(client, "get_document", Map.of("index", "app-stream", "id", DOC_ID))))
                    .contains("\"error\":\"invalid_argument\"", "data stream");
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.path().contains("/_doc/"));
    }

    @Test
    void isListedAsReadOnlyWithAClosedSchema() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.Tool tool = client.listTools().tools().stream()
                    .filter(t -> t.name().equals("get_document")).findFirst().orElseThrow();

            assertThat(tool.annotations().readOnlyHint()).isTrue();
            Map<String, Object> schema = tool.inputSchema();
            assertThat(schema).containsEntry("additionalProperties", false);
            assertThat(schema).extractingByKey("required").asInstanceOf(InstanceOfAssertFactories.LIST)
                    .containsExactlyInAnyOrder("index", "id");
            Map<String, Object> properties = properties(schema);
            assertThat(properties.keySet()).containsExactlyInAnyOrder("index", "id");
            assertThat(properties.get("index")).asInstanceOf(InstanceOfAssertFactories.MAP).containsEntry("maxLength", 255)
                    .as("the pattern comes from the contracts, not from a copy in the server")
                    .containsEntry("pattern", "^(?!\\.{1,2}$)[a-z0-9.][a-z0-9._-]{0,254}$");
            assertThat(properties.get("id")).asInstanceOf(InstanceOfAssertFactories.MAP).containsEntry("maxLength", 256);
        }
    }

    @Test
    void withoutCredentialAsksToConnectTheAccountAndDoesNotCallOpenSearch() {
        try (McpSyncClient client = clientWithCredential(null)) {
            assertCredentialRequired(call(client, "get_document", Map.of("index", "logs-team-a-2026.10.27", "id", DOC_ID)));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void credentialRejectedByOpenSearchAsksToReconnect() {
        try (McpSyncClient client = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            assertCredentialRequired(call(client, "get_document", Map.of("index", "logs-team-a-2026.10.27", "id", DOC_ID)));
        }
    }

    @Test
    void theAdapterHasNoCredentialOutsideAGatewayRequest() {
        assertThatThrownBy(() -> openSearchPort.findDocument("logs-team-a-2026.10.27", DOC_ID))
                .isInstanceOf(CredentialRequiredException.class);
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void missingForbiddenHiddenAndLeakingTargetsLookTheSame() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            String missingDocument = text(call(client, "get_document",
                    Map.of("index", "logs-team-a-2026.10.27", "id", "no-such-doc")));
            assertThat(missingDocument).contains("\"error\":\"not_found_or_denied\"");
            for (String index : new String[] {"logs-team-a-restricted", "logs-team-a-missing", "logs-team-a-hidden",
                    "app-leak"}) {
                assertThat(text(call(client, "get_document", Map.of("index", index, "id", DOC_ID))))
                        .as(index).isEqualTo(missingDocument);
            }
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.path().startsWith("/secret-hr-1"));
    }

    @Test
    void invalidIndicesAndIdsAreRejectedBeforeOpenSearch() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String index : new String[] {".security", ".ds-logs-team-a-stream-000001", "_all", "*", "logs-team-a-*",
                    "logs-team-a-1,secret-hr-1", "remote:logs-team-a-1", "<logs-team-a-{now/d}>", "-logs-team-a-1",
                    "Logs-team-a-1", "..", "", "a".repeat(256)}) {
                McpSchema.CallToolResult result = call(client, "get_document", Map.of("index", index, "id", DOC_ID));

                assertInvalidArgument(result);
                if (index.length() > 3) {
                    assertThat(text(result)).as("the value is not echoed").doesNotContain(index);
                }
            }
            for (String id : new String[] {"../_search", "a/b", "a?b", "a#b", "a%2Fb", ".", "..", "a b", "", "x".repeat(257)}) {
                McpSchema.CallToolResult result = call(client, "get_document",
                        Map.of("index", "logs-team-a-2026.10.27", "id", id));

                assertInvalidArgument(result);
                if (id.length() > 3) {
                    assertThat(text(result)).as("the value is not echoed").doesNotContain(id);
                }
            }
            assertInvalidArgument(call(client, "get_document", Map.of("index", "logs-team-a-2026.10.27")));
            assertInvalidArgument(call(client, "get_document",
                    Map.of("index", "logs-team-a-2026.10.27", "id", DOC_ID, "_source", "*")));
        }
        assertThat(BACKEND.requests()).isEmpty();
    }

    @Test
    void anIndexOutsideTheScopeLooksLikeAMissingOne() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            String missing = text(call(client, "get_document", Map.of("index", "logs-team-a-missing", "id", DOC_ID)));
            assertThat(missing).contains("\"error\":\"not_found_or_denied\"");
            for (String outside : new String[] {"secret-hr-1", "logs-team-ab-1", "logs"}) {
                assertThat(text(call(client, "get_document", Map.of("index", outside, "id", DOC_ID)))).as(outside)
                        .isEqualTo(missing);
            }
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.decodedPath().startsWith("/secret-hr-1")
                || r.decodedPath().startsWith("/logs-team-ab-1") || r.decodedPath().contains("/logs/"));
    }

    @Test
    void anAliasToAHiddenIndexIsNotRead() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_document", Map.of("index", "app-hidden-target", "id", DOC_ID));

            assertThat(text(result)).contains("\"error\":\"not_found_or_denied\"");
        }
        assertThat(BACKEND.requests()).noneMatch(r -> r.path().contains("/_doc/"));
    }

    @Test
    void anUnreadableBodyStaysOutOfTheResultAndTheLog(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_document",
                    Map.of("index", "logs-team-a-2026.10.27", "id", "doc-badjson"));

            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain(StubBackend.BODY_MARKER);
        }
        assertThat(output.getAll()).contains("BackendResponseException").doesNotContain(StubBackend.BODY_MARKER);
    }

    @Test
    void aRedirectIsNotFollowedAndNotReadAsMissing() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_document",
                    Map.of("index", "logs-team-a-2026.10.27", "id", "doc-302"));

            assertThat(text(result)).contains("\"error\":\"internal_error\"");
        }
        assertThat(BACKEND.elsewhereHits()).as("the credential must not follow the Location").isEmpty();
    }

    @Test
    void aSlowBackendTimesOut() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            long start = System.nanoTime();
            McpSchema.CallToolResult result = call(client, "get_document",
                    Map.of("index", "logs-team-a-2026.10.27", "id", "doc-slow"));

            assertThat(text(result)).contains("\"error\":\"upstream_unavailable\"");
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(java.time.Duration.ofMillis(StubBackend.SLOW_MILLIS));
        }
    }

    @Test
    void anOversizedBodyIsCutOff() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_document",
                    Map.of("index", "logs-team-a-2026.10.27", "id", "doc-huge"));

            assertThat(text(result)).contains("\"error\":\"internal_error\"");
        }
    }
}
