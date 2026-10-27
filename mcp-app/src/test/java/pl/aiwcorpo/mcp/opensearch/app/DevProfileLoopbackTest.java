package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/**
 * The dev profile (test keys, no mTLS) does not start on an address other machines can reach. The check is the
 * starter's (3.0.0-alpha-5); this test keeps it from disappearing unnoticed.
 */
class DevProfileLoopbackTest {

    @Test
    void devOnAnyInterfaceDoesNotStart() {
        try (TestGateway gateway = TestGateway.start(ServerTestSupport.SERVER_ID)) {
            SpringApplicationBuilder app = new SpringApplicationBuilder(McpServerApplication.class).profiles("dev");
            gateway.serverProperties().forEach((name, value) -> app.properties(name + "=" + value));

            // Command line, like SERVER_ADDRESS would: above application-dev.yml.
            assertThatThrownBy(() -> app.run("--server.address=0.0.0.0", "--server.port=0").close())
                    .hasStackTraceContaining("server.address must be a loopback address");
        }
    }
}
