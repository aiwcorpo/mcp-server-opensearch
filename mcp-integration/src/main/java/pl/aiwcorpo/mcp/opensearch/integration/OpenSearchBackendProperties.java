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
 * @param assumeTestedVersion what the version probe reports when {@code GET /} refuses the anonymous request (the usual
 *                        answer of a cluster with the Security plugin): true = the operator states the cluster is OpenSearch
 *                        2.x or 3.x and the server runs with the full feature set; false = degraded mode. The server holds
 *                        no credential of its own, so the probe cannot log in
 */
@ConfigurationProperties("opensearch.backend")
public record OpenSearchBackendProperties(String baseUrl, String sslBundle, Duration connectTimeout,
        Duration readTimeout, DataSize maxResponseSize, boolean allowPlainHttp, boolean assumeTestedVersion) {

    public OpenSearchBackendProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        maxResponseSize = maxResponseSize == null ? DataSize.ofMegabytes(4) : maxResponseSize;
    }
}
