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
import java.util.Set;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
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
abstract class ServerTestSupport {

    static final String SERVER_ID = "mcp-opensearch";
    static final String SYSTEM = "opensearch";
    static final String ENDPOINT = "/mcp";
    static final Set<String> CONTRACT_SYSTEMS = contractSystems();

    static final TestGateway GATEWAY = TestGateway.start(SERVER_ID);
    static final StubBackend BACKEND = StubBackend.start();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
        registry.add("opensearch.backend.base-url", BACKEND::baseUrl);
        registry.add("opensearch.scope.allowed-index-prefixes", () -> "logs-team-a,app");
    }

    @LocalServerPort
    int port;

    String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** An MCP client that reaches the server the way the gateway does, with {@code credential} as C3 header. */
    McpSyncClient clientWithCredential(String credential) {
        return clientWithCredential(credential, "user-secret");
    }

    /** As {@link #clientWithCredential(String)}, with another {@code X-MCP-Credential-Type} (C3). */
    McpSyncClient clientWithCredential(String credential, String credentialType) {
        McpSyncHttpClientRequestCustomizer gateway = GATEWAY.asGateway(user -> user.username("jkowalski"), credential);
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

    /**
     * The starter sends only C10 errors valid against {@code tool-error.schema.json}, whose {@code system} is the
     * closed {@code SystemId} list of the contracts. For a system outside it (the template's placeholder, a
     * trial name) a {@code credential_required} becomes {@code internal_error}; for the platform's systems the
     * full error with the portal link is expected.
     */
    static void assertCredentialRequired(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isTrue();
        if (CONTRACT_SYSTEMS.contains(SYSTEM)) {
            assertThat(text(result)).contains("\"error\":\"credential_required\"", "\"system\":\"" + SYSTEM + "\"",
                    "\"connectUrl\":\"http://localhost:18090/connections?system=" + SYSTEM + "\"");
        } else {
            assertThat(text(result)).contains("\"error\":\"internal_error\"");
        }
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
     * The MCP SDK checks arguments against the input schema before the tool runs and answers with its own text
     * ("input validation failed"), not a C10 error; arguments that pass the schema reach the tool's own checks
     * ({@code invalid_argument}). Either way the call is refused before the backend.
     */
    static void assertRejectedArguments(McpSchema.CallToolResult result, String toolMessage) {
        assertThat(result.isError()).isTrue();
        String text = text(result);
        if (!text.contains("input validation failed")) {
            assertThat(text).contains("\"error\":\"invalid_argument\"", toolMessage);
        }
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
