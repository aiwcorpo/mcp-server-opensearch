package pl.aiwcorpo.mcp.opensearch.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import pl.aiwcorpo.mcp.opensearch.core.domain.OpenSearchFeatures;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;
import pl.aiwcorpo.mcp.platform.capability.CapabilityProbe;
import pl.aiwcorpo.mcp.platform.capability.ProductVersion;

/**
 * Reads {@code GET /} of the cluster once at startup and then on the interval of the platform: the version and the
 * distribution decide whether the server runs with the verified feature set (OpenSearch 2.x and 3.x) or with the
 * baseline. The probe has no user, so it sends no credential of a user. A cluster with the Security plugin usually
 * refuses an anonymous {@code GET /} with 401: that is an answer, but without a version, so the state is
 * {@code degraded} until {@code opensearch.backend.probe-authorization} gives the probe a header of its own.
 *
 * <p>Only the version number and the distribution name are read from the answer; the cluster name, node name and
 * build data are ignored.
 */
@Component
public class OpenSearchCapabilityProbe implements CapabilityProbe {

    /** First version the server was written for; 1.x is out of support and 4.x has not been tested. */
    static final ProductVersion FIRST_TESTED = new ProductVersion(2, 0, 0);
    static final int LAST_TESTED_MAJOR = 3;
    /** The answer of {@code GET /} is under 1 KiB in practice. */
    private static final long MAX_BODY_BYTES = 16 * 1024;
    private static final String DEV_PROFILE = "dev";

    private final RestClient client;
    private final String authorization;

    public OpenSearchCapabilityProbe(OpenSearchBackendProperties properties, ObjectProvider<SslBundles> sslBundles,
            Environment environment) {
        this.authorization = properties.probeAuthorization() == null || properties.probeAuthorization().isBlank()
                ? null : properties.probeAuthorization().strip();
        this.client = RestClient.builder()
                .baseUrl(OpenSearchAdapter.requireBaseUrl(properties.baseUrl(),
                        environment.matchesProfiles(DEV_PROFILE) || properties.allowPlainHttp()))
                .requestFactory(OpenSearchAdapter.requestFactory(properties, sslBundles))
                .requestInterceptor(new BoundedBodyInterceptor(MAX_BODY_BYTES))
                .build();
    }

    @Override
    public String name() {
        return OpenSearchFeatures.PROBE;
    }

    /** Details go through the platform's character filter ({@code A-Za-z0-9._+ -}), so they are written without punctuation. */
    @Override
    public Capabilities probe() {
        RootResponse root;
        try {
            root = client.get().uri("/")
                    .headers(headers -> {
                        if (authorization != null) {
                            headers.set(HttpHeaders.AUTHORIZATION, authorization);
                        }
                    })
                    .retrieve()
                    .body(RootResponse.class);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden refused) {
            return Capabilities.degraded(null, OpenSearchFeatures.BASELINE,
                    "the root endpoint refused the request - set the probe authorization to read the version");
        }
        return classify(root);
    }

    static Capabilities classify(RootResponse root) {
        if (root == null || root.version() == null || root.version().number() == null) {
            return Capabilities.unavailable("the root endpoint has no version");
        }
        Optional<ProductVersion> parsed = ProductVersion.parse(root.version().number());
        if (parsed.isEmpty()) {
            return Capabilities.unavailable("the root endpoint has no version number");
        }
        ProductVersion version = parsed.get();
        String number = version.toString();
        if (!"opensearch".equals(root.version().distribution())) {
            return Capabilities.degraded(number, OpenSearchFeatures.BASELINE, "the distribution is not OpenSearch");
        }
        if (version.compareTo(FIRST_TESTED) < 0 || version.major() > LAST_TESTED_MAJOR) {
            return Capabilities.degraded(number, OpenSearchFeatures.BASELINE, "version outside 2.x and 3.x");
        }
        return Capabilities.supported(number, OpenSearchFeatures.TESTED);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RootResponse(Version version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Version(String number, String distribution) {
    }
}
