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
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import pl.aiwcorpo.mcp.opensearch.core.domain.AggregateQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.ClusterHealth;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexInfo;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexMapping;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogCount;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogPage;
import pl.aiwcorpo.mcp.opensearch.core.domain.MappingField;
import pl.aiwcorpo.mcp.opensearch.core.domain.QueryCheck;
import pl.aiwcorpo.mcp.opensearch.core.domain.SearchAfter;
import pl.aiwcorpo.mcp.opensearch.core.domain.GroupBy;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogBucket;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogBuckets;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogFilter;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpenSearchPort;
import pl.aiwcorpo.mcp.platform.resource.QueryScope;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;

/**
 * Calls the OpenSearch REST API (2.x and 3.x) as the user: with the credential the gateway resolved for this request
 * (C3), never with an account of the server. OpenSearch Security then applies the user's own index permissions
 * and DLS/FLS as a second layer behind the prefix checks of this server.
 *
 * <p>Only these endpoints are used: {@code GET _resolve/index/{expr}} (the scope of the caller, never an
 * argument of the model), {@code POST {names}/_search} with a body the server builds (a bool query of the model's
 * values; a terms aggregation on a configured field for {@code aggregate_logs}; size 0 with an exact total for
 * {@code count_logs}), {@code POST {names}/_validate/query}, {@code GET _cat/indices/{names}}, {@code GET {index}/_mapping},
 * {@code GET {index}/_doc/{id}} and {@code GET _cluster/health}. All of them but the cluster health go to concrete index names. No
 * {@code _msearch}, {@code _mget}, scripts, {@code _count}, SQL, PPL or {@code ?q=}. 404 and 403 both become "not visible"
 * ({@code not_found_or_denied}), 401 means the stored credential no longer works
 * ({@code credential_required}). A failed call becomes an exception with the status alone, an unusable answer
 * (redirect, unparsable or oversized body) a {@link BackendResponseException}, so no response body reaches a
 * message or a log line.
 */
@Component
@EnableConfigurationProperties({OpenSearchBackendProperties.class, OpenSearchSearchProperties.class})
public class OpenSearchAdapter implements OpenSearchPort {

    /** System id of this server: {@code server-id} is {@code mcp-<system>}, and the starter refuses any other. */
    static final String SYSTEM = "opensearch";

    private static final String DEV_PROFILE = "dev";
    private static final Logger log = LoggerFactory.getLogger(OpenSearchAdapter.class);

    /**
     * Open indices only: wildcards never expand to hidden or closed indices; missing names are skipped. The starter
     * owns the list ({@link QueryScope#OPENSEARCH_SEARCH_PARAMETERS}) and every request that carries an index
     * expression sends all of it.
     */
    private static final String RESOLVE_OPTIONS = QueryScope.OPENSEARCH_RESOLVE_PARAMETERS.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(parameter -> parameter.getKey() + "=" + parameter.getValue())
            .collect(Collectors.joining("&"));

    private static final String INDICES_OPTIONS = QueryScope.OPENSEARCH_SEARCH_PARAMETERS.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(parameter -> parameter.getKey() + "=" + parameter.getValue())
            .collect(Collectors.joining("&"));

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private final RestClient client;
    private final OpenSearchSearchProperties search;
    /** The search is told to stop at 80% of the read timeout and cancelled at 90%, so the cluster stops before the client gives up. */
    private final String searchTimeout;
    private final String cancelAfter;

    public OpenSearchAdapter(OpenSearchBackendProperties properties, OpenSearchSearchProperties search,
            ObjectProvider<SslBundles> sslBundles, Environment environment) {
        this.search = search;
        long readMillis = properties.readTimeout().toMillis();
        this.searchTimeout = Math.max(1, readMillis * 8 / 10) + "ms";
        this.cancelAfter = Math.max(1, readMillis * 9 / 10) + "ms";
        this.client = RestClient.builder()
                .baseUrl(requireBaseUrl(properties.baseUrl(),
                        environment.matchesProfiles(DEV_PROFILE) || properties.allowPlainHttp()))
                .requestFactory(requestFactory(properties, sslBundles))
                .requestInterceptor(new BoundedBodyInterceptor(properties.maxResponseSize().toBytes()))
                .defaultStatusHandler(status -> status.isError() || status.is3xxRedirection(), (request, response) -> {
                    // Status only: the body and headers of a failed call may echo the credential or carry backend
                    // detail (OpenSearch puts index names and query fragments in its error reasons).
                    HttpStatusCode status = response.getStatusCode();
                    if (status.is3xxRedirection()) {
                        // Redirects are not followed (the credential must not go elsewhere) and an empty body
                        // must not read as "not found": an SSO login page would turn into not_found_or_denied.
                        throw new BackendResponseException("redirect " + status.value());
                    }
                    throw status.is4xxClientError()
                            ? HttpClientErrorException.create(status, "", HttpHeaders.EMPTY, new byte[0], null)
                            : HttpServerErrorException.create(status, "", HttpHeaders.EMPTY, new byte[0], null);
                })
                .build();
    }

    @Override
    public String resolveIndexResponse(String expression) {
        BackendCredential credential = credential();
        try {
            String response = client.get()
                    .uri("/_resolve/index/{expression}?" + RESOLVE_OPTIONS, expression)
                    .headers(credential::applyTo)
                    .retrieve()
                    .body(String.class);
            if (response == null) {
                throw new BackendResponseException("empty body");
            }
            requireIndexList(response);
            return response;
        } catch (HttpClientErrorException.NotFound noIndex) {
            // None of the names exists, and for a name written as plain "p" that is the usual case (only p-2026.10 does).
            return QueryScope.EMPTY_RESOLVE_RESPONSE;
        } catch (HttpClientErrorException.Forbidden hidden) {
            throw new NotFoundOrDeniedException();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        } catch (RestClientResponseException | ResourceAccessException statusOrIo) {
            throw statusOrIo;
        } catch (RestClientException unreadable) {
            throw new BackendResponseException("body not readable");
        }
    }

    /** The platform library reads the answer as JSON; an SSO page or a proxy error must not surface as invalid_argument. */
    private static void requireIndexList(String response) {
        try {
            if (!JSON.readTree(response).path("indices").isArray()) {
                throw new BackendResponseException("no indices list");
            }
        } catch (tools.jackson.core.JacksonException unreadable) {
            throw new BackendResponseException("body not readable");
        }
    }

    @Override
    public LogPage search(List<String> indices, IndexScope scope, LogQuery query) {
        BackendCredential credential = credential();
        SearchResponse response;
        try {
            response = client.post()
                    .uri("/{expression}/_search?" + INDICES_OPTIONS + "&allow_partial_search_results=false&cancel_after_time_interval=" + cancelAfter,
                            String.join(",", indices))
                    .headers(credential::applyTo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(searchBody(scope, query))
                    .retrieve()
                    .body(SearchResponse.class);
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden hidden) {
            throw new NotFoundOrDeniedException();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        } catch (RestClientResponseException | ResourceAccessException statusOrIo) {
            throw statusOrIo;
        } catch (RestClientException unreadable) {
            throw new BackendResponseException("body not readable");
        }
        if (response == null || response.hits() == null || response.hits().hits() == null) {
            throw new BackendResponseException("no hits section");
        }
        List<LogHit> hits = new ArrayList<>();
        SearchAfter next = null;
        for (Hit hit : response.hits().hits()) {
            UntrustedValues.Flattened data = UntrustedValues.flatten(pick(hit.source()));
            SearchAfter position = position(hit.sort());
            hits.add(new LogHit(hit.index(), UntrustedValues.documentId(hit.id()),
                    UntrustedValues.timestamp(valueAt(hit.source(), search.timestampField())), data.fields(),
                    data.truncated(), position));
            next = position;
        }
        boolean full = response.hits().hits().size() >= query.size();
        return new LogPage(hits, full ? next : null);
    }

    /** The two sort values of a hit as a position; empty when paging is off or a value cannot go into a cursor. */
    private SearchAfter position(List<Object> sort) {
        if (!search.pagingEnabled() || sort == null || sort.size() != 2) {
            return null;
        }
        return SearchAfter.of(sort.get(0), sort.get(1)).orElse(null);
    }

    @Override
    public LogCount count(List<String> indices, IndexScope scope, LogQuery query) {
        BackendCredential credential = credential();
        CountResponse response = send(() -> client.post()
                .uri("/{expression}/_search?" + INDICES_OPTIONS + "&allow_partial_search_results=false&cancel_after_time_interval=" + cancelAfter,
                        String.join(",", indices))
                .headers(credential::applyTo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(countBody(scope, query))
                .retrieve()
                .body(CountResponse.class));
        if (response == null || response.hits() == null || response.hits().total() == null
                || response.hits().total().value() == null) {
            throw new BackendResponseException("no total");
        }
        boolean exact = "eq".equals(response.hits().total().relation()) && !Boolean.TRUE.equals(response.timedOut());
        return new LogCount(response.hits().total().value(), exact);
    }

    @Override
    public QueryCheck validate(List<String> indices, IndexScope scope, LogQuery query) {
        BackendCredential credential = credential();
        ValidateResponse response = send(() -> client.post()
                .uri("/{expression}/_validate/query?explain=true&" + INDICES_OPTIONS, String.join(",", indices))
                .headers(credential::applyTo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", queryClause(scope, query.filter(), query.from(), query.to())))
                .retrieve()
                .body(ValidateResponse.class));
        if (response == null || response.valid() == null) {
            throw new BackendResponseException("no validity");
        }
        List<Explanation> explanations = response.explanations() == null ? List.of() : response.explanations();
        List<String> invalid = explanations.stream()
                .filter(e -> Boolean.FALSE.equals(e.valid()) && e.index() != null && scope.covers(e.index()))
                .map(Explanation::index).limit(QueryCheck.MAX_INVALID).toList();
        String first = explanations.stream().map(Explanation::explanation).filter(java.util.Objects::nonNull)
                .findFirst().orElse("");
        return new QueryCheck(response.valid(), explanations.size(), invalid, UntrustedValues.text(first));
    }

    @Override
    public List<IndexInfo> indexInfo(List<String> indices) {
        BackendCredential credential = credential();
        List<Map<String, String>> rows = send(() -> client.get()
                .uri("/_cat/indices/{expression}?format=json&h=index,health&"
                        + INDICES_OPTIONS, String.join(",", indices))
                .headers(credential::applyTo)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<List<Map<String, String>>>() { }));
        if (rows == null) {
            throw new BackendResponseException("no rows");
        }
        Map<String, IndexInfo> byName = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String name = row.get("index");
            if (name != null) {
                byName.put(name, new IndexInfo(name, health(row.get("health"))));
            }
        }
        return indices.stream().map(byName::get).filter(java.util.Objects::nonNull).toList();
    }

    private static String health(String value) {
        return "green".equals(value) || "yellow".equals(value) || "red".equals(value) ? value : null;
    }

    @Override
    public Optional<IndexMapping> findMapping(String index) {
        BackendCredential credential = credential();
        Map<String, MappingResponse> response;
        try {
            response = client.get()
                    .uri("/{index}/_mapping", index)
                    .headers(credential::applyTo)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, MappingResponse>>() { });
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden hidden) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        } catch (RestClientResponseException | ResourceAccessException statusOrIo) {
            throw statusOrIo;
        } catch (RestClientException unreadable) {
            throw new BackendResponseException("body not readable");
        }
        if (response == null || response.isEmpty()) {
            throw new BackendResponseException("empty body");
        }
        MappingResponse mapping = response.get(index);
        if (mapping == null) {
            return Optional.empty();
        }
        List<MappingField> fields = new ArrayList<>();
        boolean[] truncated = {false};
        if (mapping.mappings() != null) {
            collectFields(fields, truncated, "", mapping.mappings().get("properties"), 0, search.mappingFields());
        }
        return Optional.of(new IndexMapping(index, fields, truncated[0]));
    }

    private static final int MAX_MAPPING_FIELDS = 500;
    private static final int MAX_MAPPING_DEPTH = 10;
    private static final java.util.regex.Pattern MAPPING_TYPE = java.util.regex.Pattern.compile("[a-z][a-z0-9_]{0,31}");

    /** Only fields this server uses (and their sub-fields) are listed; the rest of the schema of the logs stays with OpenSearch. */
    private static void collectFields(List<MappingField> out, boolean[] truncated, String prefix, Object properties,
            int depth, java.util.Set<String> shown) {
        if (!(properties instanceof Map<?, ?> map)) {
            return;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> definition)) {
                continue;
            }
            if (out.size() >= MAX_MAPPING_FIELDS || depth >= MAX_MAPPING_DEPTH) {
                truncated[0] = true;
                return;
            }
            String path = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            Object type = definition.get("type");
            if (type instanceof String name && shown.stream().anyMatch(field -> path.equals(field) || path.startsWith(field + "."))) {
                out.add(new MappingField(UntrustedValues.label(path), MAPPING_TYPE.matcher(name).matches() ? name : "other"));
            }
            collectFields(out, truncated, path, definition.get("properties"), depth + 1, shown);
            collectFields(out, truncated, path, definition.get("fields"), depth + 1, shown);
        }
    }

    @Override
    public ClusterHealth clusterHealth() {
        BackendCredential credential = credential();
        HealthResponse response = send(() -> client.get()
                .uri("/_cluster/health")
                .headers(credential::applyTo)
                .retrieve()
                .body(HealthResponse.class));
        if (response == null || !("green".equals(response.status()) || "yellow".equals(response.status())
                || "red".equals(response.status())) || response.nodes() == null || response.activeShards() == null) {
            throw new BackendResponseException("no health");
        }
        return new ClusterHealth(response.status(), Boolean.TRUE.equals(response.timedOut()), response.nodes(),
                response.activeShards(), orZero(response.relocating()), orZero(response.initializing()),
                orZero(response.unassigned()), response.percent() == null ? 0 : response.percent());
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }

    /** One backend call with the status mapping every tool shares: 404 and 403 are "not visible", 401 an expired credential. */
    private static <T> T send(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden hidden) {
            throw new NotFoundOrDeniedException();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        } catch (RestClientResponseException | ResourceAccessException statusOrIo) {
            throw statusOrIo;
        } catch (RestClientException unreadable) {
            throw new BackendResponseException("body not readable");
        }
    }

    @Override
    public LogBuckets aggregate(List<String> indices, IndexScope scope, AggregateQuery query) {
        BackendCredential credential = credential();
        AggregateResponse response;
        try {
            // The names come from QueryScope.opensearchSearchTarget: each matches the contract's index name pattern,
            // so none has a comma or a wildcard, and they are joined with commas here.
            response = client.post()
                    .uri("/{expression}/_search?" + INDICES_OPTIONS + "&allow_partial_search_results=false&cancel_after_time_interval=" + cancelAfter,
                            String.join(",", indices))
                    .headers(credential::applyTo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(aggregateBody(scope, query))
                    .retrieve()
                    .body(AggregateResponse.class);
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden hidden) {
            throw new NotFoundOrDeniedException();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        } catch (RestClientResponseException | ResourceAccessException statusOrIo) {
            throw statusOrIo;
        } catch (RestClientException unreadable) {
            throw new BackendResponseException("body not readable");
        }
        if (response == null || response.aggregations() == null || response.aggregations().groups() == null
                || response.aggregations().groups().buckets() == null) {
            throw new BackendResponseException("no aggregation section");
        }
        List<LogBucket> buckets = new ArrayList<>();
        for (Bucket bucket : response.aggregations().groups().buckets()) {
            if (bucket.key() != null && bucket.count() != null) {
                buckets.add(new LogBucket(UntrustedValues.label(String.valueOf(bucket.key())), bucket.count()));
            }
        }
        long other = response.aggregations().groups().otherDocuments() == null ? 0 : response.aggregations().groups().otherDocuments();
        return new LogBuckets(buckets, other);
    }

    @Override
    public Optional<IndexDocument> findDocument(String index, String id) {
        BackendCredential credential = credential();
        DocumentResponse response;
        try {
            response = client.get()
                    .uri("/{index}/_doc/{id}?_source_includes={fields}", index, id, String.join(",", search.resultFields()))
                    .headers(credential::applyTo)
                    .retrieve()
                    .body(DocumentResponse.class);
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden hidden) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized expired) {
            throw new CredentialRequiredException(SYSTEM);
        } catch (RestClientResponseException | ResourceAccessException statusOrIo) {
            throw statusOrIo;
        } catch (RestClientException unreadable) {
            throw new BackendResponseException("body not readable");
        }
        if (response == null) {
            throw new BackendResponseException("empty body");
        }
        if (!Boolean.TRUE.equals(response.found()) || response.index() == null) {
            return Optional.empty();
        }
        UntrustedValues.Flattened data = UntrustedValues.flatten(pick(response.source()));
        return Optional.of(new IndexDocument(response.index(), UntrustedValues.documentId(response.id()),
                data.fields(), data.truncated()));
    }

    /**
     * OpenSearch Security takes the user's own token, exchanged by the broker, as {@code Bearer}. The token is
     * used for the one request it arrived with and never kept, so it cannot outlive its {@code expiresAt}. Any other
     * type or scheme would only fail at OpenSearch, or (a service account) replace the user's DLS/FLS with the
     * server's, so it is refused here and the user is sent to the portal.
     */
    private static BackendCredential credential() {
        BackendCredential credential = CredentialContext.require(SYSTEM);
        if (credential.type() != BackendCredential.Type.TOKEN_EXCHANGE
                || credential.scheme() != BackendCredential.Scheme.BEARER) {
            log.warn("event=credential_unsupported type={} scheme={}", credential.type().wireName(),
                    credential.scheme().wireName());
            throw new CredentialRequiredException(SYSTEM);
        }
        return credential;
    }

    private Map<String, Object> searchBody(IndexScope scope, LogQuery query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", query.size());
        body.put("track_total_hits", false);
        body.put("timeout", searchTimeout);
        body.put("_source", Map.of("includes", search.resultFields()));
        List<Object> sort = new ArrayList<>();
        sort.add(Map.of(search.timestampField(), orderedMap("order", "desc", "unmapped_type", "date")));
        if (search.pagingEnabled()) {
            sort.add(Map.of(search.tiebreakerField(), orderedMap("order", "asc", "unmapped_type", search.tiebreakerType())));
            if (query.after() != null) {
                body.put("search_after", List.of(query.after().timestamp(), query.after().tiebreaker()));
            }
        }
        body.put("sort", sort);
        body.put("query", queryClause(scope, query.filter(), query.from(), query.to()));
        return body;
    }

    private static Map<String, Object> orderedMap(String firstKey, Object firstValue, String secondKey, Object secondValue) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(firstKey, firstValue);
        map.put(secondKey, secondValue);
        return map;
    }

    private Map<String, Object> countBody(IndexScope scope, LogQuery query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", 0);
        body.put("track_total_hits", true);
        body.put("timeout", searchTimeout);
        body.put("query", queryClause(scope, query.filter(), query.from(), query.to()));
        return body;
    }

    private Map<String, Object> queryClause(IndexScope scope, LogFilter filter, java.time.Instant from, java.time.Instant to) {
        return Map.of("bool", Map.of("filter", filters(scope, filter, from, to)));
    }

    private Map<String, Object> aggregateBody(IndexScope scope, AggregateQuery query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", 0);
        body.put("track_total_hits", false);
        body.put("timeout", searchTimeout);
        body.put("query", queryClause(scope, query.filter(), query.from(), query.to()));
        body.put("aggs", Map.of("groups", Map.of("terms", Map.of("field", groupField(query.groupBy()),
                "size", query.buckets()))));
        return body;
    }

    /**
     * Every clause is built from values by the server: the model's text goes into {@code multi_match}, which reads
     * it as words (no operators, no field names, no wildcards), and level and service into exact {@code term}s.
     * The {@code _index} clause repeats the scope in the query, so an alias created after the resolve step still
     * cannot bring in an index outside it.
     */
    private List<Object> filters(IndexScope scope, LogFilter filter, java.time.Instant from, java.time.Instant to) {
        List<Object> indexClauses = new ArrayList<>();
        for (String prefix : scope.prefixes()) {
            indexClauses.add(Map.of("term", Map.of("_index", prefix)));
            indexClauses.add(Map.of("prefix", Map.of("_index", prefix + "-")));
        }
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("gte", from.toString());
        range.put("lt", to.toString());
        range.put("format", "strict_date_optional_time");

        List<Object> clauses = new ArrayList<>();
        clauses.add(Map.of("range", Map.of(search.timestampField(), range)));
        clauses.add(Map.of("bool", Map.of("should", indexClauses, "minimum_should_match", 1)));
        if (filter.text() != null) {
            Map<String, Object> words = new LinkedHashMap<>();
            words.put("query", filter.text());
            words.put("fields", search.queryFields());
            words.put("type", "best_fields");
            words.put("operator", "and");
            words.put("lenient", true);
            clauses.add(Map.of("multi_match", words));
        }
        if (filter.level() != null) {
            clauses.add(Map.of("term", Map.of(search.levelField(), Map.of("value", filter.level(), "case_insensitive", true))));
        }
        if (filter.service() != null) {
            clauses.add(Map.of("term", Map.of(search.serviceField(), filter.service())));
        }
        return clauses;
    }

    private String groupField(GroupBy groupBy) {
        return switch (groupBy) {
            case LEVEL -> search.levelField();
            case SERVICE -> search.serviceField();
            case HOST -> search.hostField();
        };
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
        return picked;
    }

    /** {@code a.b} as a literal key first (flattened documents), then as a path through objects. */
    private static Object valueAt(Map<String, Object> source, String path) {
        if (source == null) {
            return null;
        }
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

    static String requireBaseUrl(String baseUrl, boolean plainHttpAllowed) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("opensearch.backend.base-url (MCP_OPENSEARCH_URL) must point at the OpenSearch API");
        }
        URI uri = URI.create(baseUrl);
        if (uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
            throw new IllegalStateException("opensearch.backend.base-url must be an absolute http(s) URL");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            // The credential comes from the gateway (C3); a userinfo here would be a second, shared one, and a query
            // or fragment would be glued onto every request path.
            throw new IllegalStateException("opensearch.backend.base-url must not carry user info, a query or a fragment");
        }
        if (!"https".equals(uri.getScheme())) {
            if (!plainHttpAllowed) {
                // The user's credential travels in a header; over http it would cross the network in clear text.
                throw new IllegalStateException("opensearch.backend.base-url must use https outside the 'dev' profile "
                        + "(opensearch.backend.allow-plain-http exists for the mocks of the dev stack only)");
            }
            log.warn("event=backend_plain_http detail=\"credentials go to the backend unencrypted\"");
        }
        return baseUrl;
    }

    static JdkClientHttpRequestFactory requestFactory(OpenSearchBackendProperties properties,
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(Hits hits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hits(List<Hit> hits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hit(@JsonProperty("_index") String index, @JsonProperty("_id") String id,
            @JsonProperty("_source") Map<String, Object> source, List<Object> sort) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AggregateResponse(Aggregations aggregations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Aggregations(Groups groups) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Groups(List<Bucket> buckets, @JsonProperty("sum_other_doc_count") Long otherDocuments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Bucket(Object key, @JsonProperty("doc_count") Long count) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocumentResponse(@JsonProperty("_index") String index, @JsonProperty("_id") String id, Boolean found,
            @JsonProperty("_source") Map<String, Object> source) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CountResponse(@JsonProperty("timed_out") Boolean timedOut, CountHits hits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CountHits(Total total) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Total(Long value, String relation) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ValidateResponse(Boolean valid, List<Explanation> explanations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Explanation(String index, Boolean valid, String explanation) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MappingResponse(Map<String, Object> mappings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HealthResponse(String status, @JsonProperty("timed_out") Boolean timedOut,
            @JsonProperty("number_of_nodes") Long nodes, @JsonProperty("active_shards") Long activeShards,
            @JsonProperty("relocating_shards") Long relocating, @JsonProperty("initializing_shards") Long initializing,
            @JsonProperty("unassigned_shards") Long unassigned,
            @JsonProperty("active_shards_percent_as_number") Double percent) {
    }
}
