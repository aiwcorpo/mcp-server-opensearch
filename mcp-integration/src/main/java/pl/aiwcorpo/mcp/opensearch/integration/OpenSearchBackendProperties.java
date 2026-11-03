package pl.aiwcorpo.mcp.opensearch.integration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Where OpenSearch is and how long and how much the server reads from it.
 *
 * @param baseUrl         base URL of the OpenSearch REST API ({@code MCP_OPENSEARCH_URL}); the server does not start
 *                        without it, and outside the dev profile it must be {@code https}
 * @param sslBundle       Spring SSL bundle trusted for an HTTPS cluster (internal CA); empty = JDK default trust
 * @param connectTimeout  time to open the connection
 * @param readTimeout     time for the whole response; the gateway gives up on the call soon after anyway
 * @param maxResponseSize larger bodies are cut off with an error instead of filling the heap
 * @param allowPlainHttp  {@code http} outside the dev profile, for the mocks of mcp-platform-dev only
 *                        ({@code MCP_OPENSEARCH_ALLOW_PLAIN_HTTP}); the credential then crosses the network in clear text
 * @param probeAuthorization complete {@code Authorization} header value for the version probe ({@code GET /}) only,
 *                        for clusters that refuse anonymous requests; never sent with a tool call. Empty = anonymous probe
 */
@ConfigurationProperties("opensearch.backend")
public record OpenSearchBackendProperties(String baseUrl, String sslBundle, Duration connectTimeout,
        Duration readTimeout, DataSize maxResponseSize, boolean allowPlainHttp, String probeAuthorization) {

    public OpenSearchBackendProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        maxResponseSize = maxResponseSize == null ? DataSize.ofMegabytes(1) : maxResponseSize;
    }

    /** Without the probe authorization: a record prints all its components. */
    @Override
    public String toString() {
        return "OpenSearchBackendProperties[baseUrl=" + baseUrl + ", sslBundle=" + sslBundle + "]";
    }
}
