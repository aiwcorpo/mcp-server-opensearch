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
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpensearchResourcePort;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;

/** {@code get_record} through the test gateway: the C3 credential reaches the backend, errors are C10. */
@ExtendWith(OutputCaptureExtension.class)
class GetRecordToolTest extends ServerTestSupport {

    private static final String PAT = "Bearer opensearch-pat-of-jkowalski-7f3a";

    @Autowired
    OpensearchResourcePort resourcePort;

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void readsTheRecordWithTheCredentialFromTheGateway() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_record", Map.of("recordId", "ABC-1"));

            assertThat(result.isError()).isFalse();
            assertThat(text(result))
                    .contains("\"id\":\"ABC-1\"", "\"project\":\"ABC\"", "\"title\":\"Quarterly report\"")
                    .as("fields outside the domain record stay in the adapter").doesNotContain("internalNotes");
            assertThat(BACKEND.authorizations()).containsExactly(PAT);
        }
    }

    @Test
    void isListedAsReadOnly() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.Tool tool = client.listTools().tools().stream()
                    .filter(t -> t.name().equals("get_record")).findFirst().orElseThrow();

            assertThat(tool.annotations().readOnlyHint()).isTrue();
            assertThat(tool.inputSchema()).extractingByKey("properties")
                    .asInstanceOf(InstanceOfAssertFactories.MAP).containsOnlyKeys("recordId");
        }
    }

    @Test
    void withoutCredentialAsksToConnectTheAccountAndDoesNotCallTheBackend() {
        try (McpSyncClient client = clientWithCredential(null)) {
            McpSchema.CallToolResult result = call(client, "get_record", Map.of("recordId", "ABC-1"));

            assertCredentialRequired(result);
            assertThat(BACKEND.authorizations()).isEmpty();
        }
    }

    @Test
    void credentialRejectedByTheBackendAsksToReconnect(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(StubBackend.EXPIRED_CREDENTIAL)) {
            McpSchema.CallToolResult result = call(client, "get_record", Map.of("recordId", "ABC-1"));

            assertCredentialRequired(result);
        }
        assertThat(output.getAll()).contains("CredentialRequiredException");
    }

    @Test
    void theAdapterHasNoCredentialOutsideAGatewayRequest() {
        assertThatThrownBy(() -> resourcePort.findById("ABC-1")).isInstanceOf(CredentialRequiredException.class);
        assertThat(BACKEND.authorizations()).isEmpty();
    }

    @Test
    void missingAndForbiddenRecordsLookTheSame() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult missing = call(client, "get_record", Map.of("recordId", "ABC-404"));
            McpSchema.CallToolResult forbidden = call(client, "get_record", Map.of("recordId", "ABC-403"));

            assertThat(missing.isError()).isTrue();
            assertThat(text(missing)).contains("\"error\":\"not_found_or_denied\"");
            assertThat(text(forbidden)).isEqualTo(text(missing));
        }
    }

    @Test
    void anInvalidIdIsRejectedBeforeTheBackend() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            for (String recordId : new String[] {"../admin", "ABC-1?x=1", "a".repeat(65), ""}) {
                McpSchema.CallToolResult result = call(client, "get_record", Map.of("recordId", recordId));

                assertThat(result.isError()).isTrue();
                assertThat(text(result)).contains("\"error\":\"invalid_argument\"", "recordId must be 1-64 characters");
            }
            assertThat(BACKEND.authorizations()).isEmpty();
        }
    }

    @Test
    void theCredentialNeverAppearsInTheResultOrTheLog(CapturedOutput output) {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            McpSchema.CallToolResult result = call(client, "get_record", Map.of("recordId", "ABC-500"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain("opensearch-pat");
        }
        assertThat(output.getAll()).contains("\"tool\":\"get_record\"").doesNotContain("opensearch-pat")
                .as("the backend's error body stays out of the log").doesNotContain("boom");
    }
}
