package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** A cluster that refuses the anonymous {@code GET /} and an operator who states that it is a tested version. */
@TestPropertySource(properties = "opensearch.backend.assume-tested-version=true")
class AssumedVersionTest extends ServerTestSupport {

    private static final String PAT = "Bearer opensearch-token-of-jkowalski-5c1e";

    @BeforeAll
    static void clusterRefusesAnonymousRoot() {
        BACKEND.anonymousRoot(false);
    }

    @AfterAll
    static void restoreRoot() {
        BACKEND.anonymousRoot(true);
    }

    @Test
    void theFullSetIsAvailableAndTheVersionIsReportedAsUnknown() {
        try (McpSyncClient client = clientWithCredential(PAT)) {
            assertThat(text(call(client, "get_backend_info", Map.of()))).contains("\"state\":\"supported\"",
                    "\"version\":\"unknown\"", "\"cluster_health\"");
            assertThat(call(client, "get_cluster_health", Map.of()).isError()).isFalse();
        }
    }
}
