package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The gateway puts {@code vs} and an empty {@code vsc} into every JWT of a call made through a link (C2, CC-027);
 * the server has to accept them. Narrowing by link scopes is tested in ResourcePolicyTest.
 */
class GatewayLinkClaimsTest extends ServerTestSupport {

    @Test
    void aCallThroughALinkWithoutNarrowingIsServed() {
        try (McpSyncClient client = clientAs(
                user -> {
                    reader(user);
                    user.virtualServer("logs-review", Map.of());
                },
                "Bearer opensearch-token-of-jkowalski-5c1e", "user-secret")) {
            McpSchema.CallToolResult result = call(client, "get_document",
                    Map.of("index", "logs-team-a-2026.10.27", "id", "Xk3n8ZIBdLrT0q1aHf2p"));

            assertThat(result.isError()).as(text(result)).isFalse();
            assertThat(text(result)).contains("\"id\":\"Xk3n8ZIBdLrT0q1aHf2p\"");
        }
    }
}
