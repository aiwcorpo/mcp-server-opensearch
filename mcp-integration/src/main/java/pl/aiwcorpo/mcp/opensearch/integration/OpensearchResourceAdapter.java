package pl.aiwcorpo.mcp.opensearch.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import pl.aiwcorpo.mcp.opensearch.core.domain.OpensearchRecord;
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpensearchResourcePort;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;

/**
 * Calls the backend API as the user: with the credential the gateway resolved for this request (C3), never
 * with an account of the server.
 *
 * <p>Error mapping matters for security: 404 and 403 both become "not visible" (one client-facing code,
 * {@code not_found_or_denied}), and 401 means the stored credential no longer works, so the user is sent to
 * the portal to reconnect ({@code credential_required}). A failed call becomes an exception with the status
 * alone, so no response body reaches a message or a log line.
 */
@Component
@EnableConfigurationProperties(OpensearchBackendProperties.class)
public class OpensearchResourceAdapter implements OpensearchResourcePort {

    /** System id of this server: {@code server-id} is {@code mcp-<system>}, and the starter refuses any other. */
    static final String SYSTEM = "opensearch";

    private final RestClient client;

    public OpensearchResourceAdapter(OpensearchBackendProperties properties, ObjectProvider<SslBundles> sslBundles) {
        this.client = RestClient.builder()
                .baseUrl(requireBaseUrl(properties.baseUrl()))
                .requestFactory(requestFactory(properties, sslBundles))
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    // Status only: the body and headers of a failed call may echo the credential or carry backend
                    // detail, and the exception message ends up in the starter's tool_failed log line.
                    HttpStatusCode status = response.getStatusCode();
                    throw status.is4xxClientError()
                            ? HttpClientErrorException.create(status, "", HttpHeaders.EMPTY, new byte[0], null)
                            : HttpServerErrorException.create(status, "", HttpHeaders.EMPTY, new byte[0], null);
                })
                .build();
    }

    @Override
    public Optional<OpensearchRecord> findById(String recordId) {
        BackendCredential credential = CredentialContext.require(SYSTEM);
        try {
            RecordResponse response = client.get()
                    .uri("/api/records/{id}", recordId)
                    .headers(credential::applyTo)
                    .retrieve()
                    .body(RecordResponse.class);
            return Optional.ofNullable(response).map(RecordResponse::toDomain);
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden hidden) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        }
    }

    private static String requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("opensearch.backend.base-url (MCP_OPENSEARCH_URL) must point at the backend API");
        }
        URI uri = URI.create(baseUrl);
        if (uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
            throw new IllegalStateException("opensearch.backend.base-url must be an absolute http(s) URL");
        }
        return baseUrl;
    }

    private static JdkClientHttpRequestFactory requestFactory(OpensearchBackendProperties properties,
            ObjectProvider<SslBundles> sslBundles) {
        // Redirects are not followed: the credential header must not travel to wherever a backend points.
        HttpClient.Builder http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        if (properties.sslBundle() != null && !properties.sslBundle().isBlank()) {
            http.sslContext(sslBundles.getObject().getBundle(properties.sslBundle()).createSslContext());
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    /** The backend's representation; unknown fields are dropped here and never reach the model. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RecordResponse(String id, String project, String title, String status) {

        OpensearchRecord toDomain() {
            return new OpensearchRecord(id, project, title, status);
        }
    }
}
