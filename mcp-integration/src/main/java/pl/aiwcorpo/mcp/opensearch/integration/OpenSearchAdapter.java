package pl.aiwcorpo.mcp.opensearch.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.opensearch.core.domain.ResolvedIndices;
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpenSearchPort;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;

/**
 * Calls the OpenSearch REST API (2.x) as the user: with the credential the gateway resolved for this request
 * (C3), never with an account of the server. OpenSearch Security then applies the user's own index permissions
 * and DLS/FLS as a second layer behind the prefix checks of this server.
 *
 * <p>Only four endpoints are used: {@code GET _resolve/index/{expr}}, {@code POST {expr}/_search} with a body
 * the server builds, and {@code GET {index}/_doc/{id}}. No {@code _msearch}, {@code _mget}, scripts,
 * aggregations or {@code ?q=}. 404 and 403 both become "not visible" ({@code not_found_or_denied}), 401 means
 * the stored credential no longer works ({@code credential_required}). A failed call becomes an exception with
 * the status alone, so no response body reaches a message or a log line.
 */
@Component
@EnableConfigurationProperties({OpenSearchBackendProperties.class, OpenSearchSearchProperties.class})
public class OpenSearchAdapter implements OpenSearchPort {

    /** System id of this server: {@code server-id} is {@code mcp-<system>}, and the starter refuses any other. */
    static final String SYSTEM = "opensearch";

    private static final Logger log = LoggerFactory.getLogger(OpenSearchAdapter.class);

    /** Open indices only: wildcards never expand to hidden or closed indices; missing names are skipped. */
    private static final String INDICES_OPTIONS = "expand_wildcards=open&ignore_unavailable=true&allow_no_indices=true";

    private final RestClient client;
    private final OpenSearchSearchProperties search;

    public OpenSearchAdapter(OpenSearchBackendProperties properties, OpenSearchSearchProperties search,
            ObjectProvider<SslBundles> sslBundles) {
        this.search = search;
        this.client = RestClient.builder()
                .baseUrl(requireBaseUrl(properties.baseUrl()))
                .requestFactory(requestFactory(properties, sslBundles))
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    // Status only: the body and headers of a failed call may echo the credential or carry backend
                    // detail (OpenSearch puts index names and query fragments in its error reasons).
                    HttpStatusCode status = response.getStatusCode();
                    throw status.is4xxClientError()
                            ? HttpClientErrorException.create(status, "", HttpHeaders.EMPTY, new byte[0], null)
                            : HttpServerErrorException.create(status, "", HttpHeaders.EMPTY, new byte[0], null);
                })
                .build();
    }

    @Override
    public Optional<ResolvedIndices> resolve(String expression) {
        BackendCredential credential = credential();
        try {
            ResolveResponse response = client.get()
                    .uri("/_resolve/index/{expression}?" + INDICES_OPTIONS, expression)
                    .headers(credential::applyTo)
                    .retrieve()
                    .body(ResolveResponse.class);
            return Optional.of(response == null ? new ResolvedIndices(List.of(), List.of(), List.of()) : response.toDomain());
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden hidden) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        }
    }

    @Override
    public List<LogHit> search(String expression, IndexScope scope, LogQuery query) {
        BackendCredential credential = credential();
        try {
            SearchResponse response = client.post()
                    .uri("/{expression}/_search?" + INDICES_OPTIONS + "&allow_partial_search_results=false", expression)
                    .headers(credential::applyTo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(searchBody(scope, query))
                    .retrieve()
                    .body(SearchResponse.class);
            if (response == null || response.hits() == null || response.hits().hits() == null) {
                return List.of();
            }
            List<LogHit> hits = new ArrayList<>();
            for (Hit hit : response.hits().hits()) {
                hits.add(new LogHit(hit.index(), UntrustedValues.cleanText(hit.id()),
                        timestamp(hit.source()), pick(hit.source())));
            }
            return hits;
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden hidden) {
            throw new NotFoundOrDeniedException();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        }
    }

    @Override
    public Optional<IndexDocument> findDocument(String index, String id) {
        BackendCredential credential = credential();
        try {
            DocumentResponse response = client.get()
                    .uri("/{index}/_doc/{id}?_source_includes={fields}", index, id, String.join(",", search.resultFields()))
                    .headers(credential::applyTo)
                    .retrieve()
                    .body(DocumentResponse.class);
            if (response == null || !Boolean.TRUE.equals(response.found()) || response.index() == null) {
                return Optional.empty();
            }
            return Optional.of(new IndexDocument(response.index(), UntrustedValues.cleanText(response.id()),
                    pick(response.source())));
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden hidden) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        }
    }

    /**
     * OpenSearch Security takes a user's own credential as {@code Bearer} (JWT, token exchange) or {@code Basic}.
     * A service account would replace the user's DLS/FLS with the server's, and other schemes would only fail
     * at OpenSearch, so both are refused here and the user is sent to the portal.
     */
    private static BackendCredential credential() {
        BackendCredential credential = CredentialContext.require(SYSTEM);
        boolean userCredential = credential.type() == BackendCredential.Type.USER_SECRET
                || credential.type() == BackendCredential.Type.TOKEN_EXCHANGE;
        boolean scheme = credential.scheme() == BackendCredential.Scheme.BEARER
                || credential.scheme() == BackendCredential.Scheme.BASIC;
        if (!userCredential || !scheme) {
            log.warn("event=credential_unsupported type={} scheme={}", credential.type().wireName(),
                    credential.scheme().wireName());
            throw new CredentialRequiredException(SYSTEM);
        }
        return credential;
    }

    private Map<String, Object> searchBody(IndexScope scope, LogQuery query) {
        List<Object> indexClauses = new ArrayList<>();
        for (String prefix : scope.prefixes()) {
            indexClauses.add(Map.of("term", Map.of("_index", prefix)));
            indexClauses.add(Map.of("prefix", Map.of("_index", prefix + "-")));
        }
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("gte", query.from().toString());
        range.put("lt", query.to().toString());
        range.put("format", "strict_date_optional_time");

        Map<String, Object> text = new LinkedHashMap<>();
        text.put("query", query.text());
        text.put("fields", search.queryFields());
        text.put("default_operator", "and");
        // No FUZZY, NEAR or SLOP: cheap queries only. Field names cannot appear in simple_query_string text.
        text.put("flags", "AND|OR|NOT|PHRASE|PREFIX|PRECEDENCE|WHITESPACE|ESCAPE");
        text.put("lenient", true);

        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", List.of(Map.of("simple_query_string", text)));
        bool.put("filter", List.of(
                Map.of("range", Map.of(search.timestampField(), range)),
                Map.of("bool", Map.of("should", indexClauses, "minimum_should_match", 1))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", query.size());
        body.put("track_total_hits", false);
        body.put("timeout", "10s");
        body.put("_source", Map.of("includes", search.resultFields()));
        body.put("sort", List.of(Map.of(search.timestampField(), Map.of("order", "desc", "unmapped_type", "date"))));
        body.put("query", Map.of("bool", bool));
        return body;
    }

    private String timestamp(Map<String, Object> source) {
        Object value = valueAt(source, search.timestampField());
        return value == null || value instanceof Map || value instanceof List ? null : UntrustedValues.cleanText(String.valueOf(value));
    }

    /**
     * The configured result fields only, keyed by their configured path. OpenSearch already applies the same
     * list as {@code _source} includes; picking again here keeps a misbehaving cluster or a mapping surprise
     * from widening the result.
     */
    private Map<String, Object> pick(Map<String, Object> source) {
        Map<String, Object> picked = new LinkedHashMap<>();
        if (source == null) {
            return picked;
        }
        for (String field : search.resultFields()) {
            Object value = valueAt(source, field);
            if (value != null) {
                picked.put(field, value);
            }
        }
        return UntrustedValues.cleanObject(picked);
    }

    /** {@code a.b} as a literal key first (flattened documents), then as a path through objects. */
    private static Object valueAt(Map<String, Object> source, String path) {
        if (source.containsKey(path)) {
            return source.get(path);
        }
        Object value = source;
        for (String part : path.split("\\.")) {
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            value = map.get(part);
        }
        return value;
    }

    private static String requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("opensearch.backend.base-url (MCP_OPENSEARCH_URL) must point at the OpenSearch API");
        }
        URI uri = URI.create(baseUrl);
        if (uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
            throw new IllegalStateException("opensearch.backend.base-url must be an absolute http(s) URL");
        }
        return baseUrl;
    }

    private static JdkClientHttpRequestFactory requestFactory(OpenSearchBackendProperties properties,
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

    /** {@code GET _resolve/index}; unknown fields are dropped here and never reach the model. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResolveResponse(List<ResolvedIndex> indices, List<ResolvedAlias> aliases,
            @JsonProperty("data_streams") List<ResolvedDataStream> dataStreams) {

        ResolvedIndices toDomain() {
            return new ResolvedIndices(
                    indices == null ? List.of() : indices.stream()
                            .map(i -> new ResolvedIndices.Index(i.name(),
                                    i.attributes() != null && i.attributes().contains("hidden")))
                            .toList(),
                    aliases == null ? List.of() : aliases.stream()
                            .map(a -> new ResolvedIndices.Alias(a.name(), a.indices() == null ? List.of() : a.indices()))
                            .toList(),
                    dataStreams == null ? List.of() : dataStreams.stream().map(ResolvedDataStream::name).toList());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResolvedIndex(String name, List<String> attributes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResolvedAlias(String name, List<String> indices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResolvedDataStream(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(Hits hits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hits(List<Hit> hits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hit(@JsonProperty("_index") String index, @JsonProperty("_id") String id,
            @JsonProperty("_source") Map<String, Object> source) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocumentResponse(@JsonProperty("_index") String index, @JsonProperty("_id") String id, Boolean found,
            @JsonProperty("_source") Map<String, Object> source) {
    }
}
