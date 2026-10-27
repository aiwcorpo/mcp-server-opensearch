package pl.aiwcorpo.mcp.opensearch.integration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where OpenSearch is and how long the server waits for it.
 *
 * @param baseUrl        base URL of the OpenSearch REST API ({@code MCP_OPENSEARCH_URL}); the server does not start without it
 * @param sslBundle      Spring SSL bundle trusted for an HTTPS cluster (internal CA); empty = JDK default trust
 * @param connectTimeout time to open the connection
 * @param readTimeout    time for the whole response; the gateway gives up on the call soon after anyway
 */
@ConfigurationProperties("opensearch.backend")
public record OpenSearchBackendProperties(String baseUrl, String sslBundle, Duration connectTimeout, Duration readTimeout) {

    public OpenSearchBackendProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }
}
