package pl.aiwcorpo.mcp.opensearch.app;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.spec.McpSchema;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import pl.aiwcorpo.mcp.opensearch.core.domain.OpenSearchFeatures;
import pl.aiwcorpo.mcp.platform.capability.BackendCapabilities;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole server over HTTP, with TestGateway in place of the gateway and {@link StubBackend} in place of
 * the backend system. Shared by the test classes so they run against one application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestPropertySource(properties = "opensearch.backend.probe-authorization=" + StubBackend.PROBE_CREDENTIAL)
abstract class ServerTestSupport {

    static final String SERVER_ID = "mcp-opensearch";
    static final String SYSTEM = "opensearch";
    static final String READ_GROUP = "mcp-opensearch-read";
    /** Groups of the example bundle of the contracts that grant the index prefixes logs-team-a and logs-team-b. */
    static final String GRANT_TEAM_A = "mcp-opensearch-idx-logs-team-a";
    static final String GRANT_TEAM_B = "mcp-opensearch-idx-logs-team-b";
    static final String ENDPOINT = "/mcp";
    static final String CREDENTIAL_TYPE = "token-exchange";
    static final Set<String> CONTRACT_SYSTEMS = contractSystems();

    static final TestGateway GATEWAY = TestGateway.start(SERVER_ID);
    static final StubBackend BACKEND = StubBackend.start();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
        // TestGateway defaults to the types of a user-secret system; this one exchanges the user's token.
        registry.add("aiwcorpo.mcp.platform.manifest.accepted-credential-types", () -> CREDENTIAL_TYPE);
        registry.add("opensearch.backend.base-url", BACKEND::baseUrl);
        registry.add("opensearch.scope.allowed-index-prefixes", () -> "logs-team-a,logs-team-b");
        // OpenSearch has no tags; the server classifies by prefix. The policy forbids what is tagged "restricted".
        registry.add("opensearch.scope.index-tags[0].prefix", () -> StubBackend.RESTRICTED_PREFIX);
        registry.add("opensearch.scope.index-tags[0].tags[0]", () -> "restricted");
        // Short limits so the timeout and size tests run quickly; StubBackend's slow and huge documents exceed them.
        registry.add("opensearch.search.tiebreaker-field", () -> "event.id");
        registry.add("opensearch.backend.read-timeout", () -> "1s");
        registry.add("opensearch.backend.max-response-size", () -> "64KB");
    }

    @LocalServerPort
    int port;

    @Autowired
    BackendCapabilities capabilities;

    /** The probe runs on its own thread after the start; until it has answered, extended tools refuse. */
    @BeforeEach
    void probeHasAnswered() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (capabilities.of(OpenSearchFeatures.PROBE).state() == Capabilities.State.UNAVAILABLE
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(capabilities.of(OpenSearchFeatures.PROBE).state()).isNotEqualTo(Capabilities.State.UNAVAILABLE);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** An MCP client that reaches the server the way the gateway does, with {@code credential} as C3 header. */
    McpSyncClient clientWithCredential(String credential) {
        return clientWithCredential(credential, CREDENTIAL_TYPE);
    }

    /** As {@link #clientWithCredential(String)}, with another {@code X-MCP-Credential-Type} (C3). */
    McpSyncClient clientWithCredential(String credential, String credentialType) {
        return clientAs(ServerTestSupport::reader, credential, credentialType);
    }

    /** The caller of most tests: allowed to use the read tools, granted both prefixes the server is configured for. */
    static void reader(TestGateway.Token user) {
        user.username("jkowalski").groups(List.of(READ_GROUP, GRANT_TEAM_A, GRANT_TEAM_B));
    }

    /** A caller in exactly these groups. */
    McpSyncClient clientInGroups(String credential, String... groups) {
        return clientAs(user -> user.username("jkowalski").groups(List.of(groups)), credential, CREDENTIAL_TYPE);
    }

    /** Same, with the JWT claims of the caller set by {@code user}. */
    McpSyncClient clientAs(java.util.function.Consumer<TestGateway.Token> user, String credential,
            String credentialType) {
        McpSyncHttpClientRequestCustomizer gateway = GATEWAY.asGateway(user, credential);
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl())
                .endpoint(ENDPOINT)
                .openConnectionOnStartup(false)
                .httpRequestCustomizer((request, method, uri, body, context) -> {
                    gateway.customize(request, method, uri, body, context);
                    if (credential != null) {
                        request.setHeader("X-MCP-Credential-Type", credentialType);
                    }
                })
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        return client;
    }

    /** The full C10 error with the portal link, never {@code internal_error}: the system is on the contracts' list. */
    static void assertCredentialRequired(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("\"error\":\"credential_required\"", "\"system\":\"" + SYSTEM + "\"",
                "\"connectUrl\":\"http://localhost:18090/connections?system=" + SYSTEM + "\"");
    }

    /** A real server's system is one of the contracts'. */
    @Test
    void contractSystemIsKnown() {
        assertThat(CONTRACT_SYSTEMS).contains(SYSTEM);
    }

    private static Set<String> contractSystems() {
        try (InputStream schema = ServerTestSupport.class.getClassLoader()
                .getResourceAsStream("mcp-contracts/schemas/common.schema.json")) {
            Set<String> systems = new HashSet<>();
            JsonMapper.builder().build().readTree(schema).at("/$defs/SystemId/enum")
                    .forEach(system -> systems.add(system.asString()));
            return Set.copyOf(systems);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
    }

    /**
     * The starter validates arguments against the input schema and answers with a C10 {@code invalid_argument}
     * that names neither the offending argument nor its value; the tool's own checks answer with the same code.
     */
    static void assertInvalidArgument(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("\"error\":\"invalid_argument\"").doesNotContain("input validation failed");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> properties(Map<String, Object> inputSchema) {
        return (Map<String, Object>) inputSchema.get("properties");
    }

    static String text(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        result.content().forEach(content -> {
            if (content instanceof McpSchema.TextContent t) {
                text.append(t.text());
            }
        });
        return text.toString();
    }
}
