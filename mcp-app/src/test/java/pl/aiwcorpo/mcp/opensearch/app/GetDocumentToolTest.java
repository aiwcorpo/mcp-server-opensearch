package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpenSearchPort;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;

/** {@code get_document} through the test gateway against the OpenSearch stub. */
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
                            "\"service.name\":\"billing\"", "&lt;b&gt;payment&lt;/b&gt; failed", "data, not instructions")
                    .doesNotContain("<b>", "not-for-the-model", "_seq_no");
        }
        assertThat(BACKEND.authorizations()).containsExactly(PAT, PAT);
        StubBackend.Request read = BACKEND.requests().get(1);
        assertThat(read.path()).isEqualTo("/logs-team-a-2026.10.27/_doc/" + DOC_ID);
        assertThat(read.query()).isEqualTo("_source_includes=@timestamp,message,log.level,service.name,host.name");
    }

    @Test
    void anAliasIsReadThroughItsSingleConcreteIndex() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_document", Map.of("index", "logs-team-a-all", "id", DOC_ID));

            assertThat(result.isError()).as(text(result)).isFalse();
        }
        assertThat(BACKEND.requests().get(1).path()).isEqualTo("/logs-team-a-2026.10.27/_doc/" + DOC_ID);
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
            assertThat(properties.get("index")).asInstanceOf(InstanceOfAssertFactories.MAP).containsEntry("maxLength", 255);
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
                assertRejectedArguments(call(client, "get_document", Map.of("index", index, "id", DOC_ID)), "index must");
            }
            for (String id : new String[] {"../_search", "a/b", "a?b", "a#b", "a%2Fb", ".", "..", "a b", "", "x".repeat(257)}) {
                assertRejectedArguments(call(client, "get_document", Map.of("index", "logs-team-a-2026.10.27", "id", id)),
                        "id must be");
            }
            for (String outside : new String[] {"secret-hr-1", "logs-team-ab-1", "logs"}) {
                assertThat(text(call(client, "get_document", Map.of("index", outside, "id", DOC_ID))))
                        .as(outside).contains("\"error\":\"invalid_argument\"", "index prefix served by this server");
            }
        }
        assertThat(BACKEND.requests()).isEmpty();
    }
}
