package pl.aiwcorpo.mcp.opensearch.integration;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpenSearchPort;
import pl.aiwcorpo.mcp.opensearch.core.port.out.ScopedIndices;
import pl.aiwcorpo.mcp.platform.error.SanitizedToolException;
import pl.aiwcorpo.mcp.platform.resource.QueryScope;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Concrete index names of the current call (CC-160). The only pattern the server sends to OpenSearch is the scope
 * expression of {@link QueryScope#opensearchIndexExpression()}, in {@code GET _resolve/index}; what the model
 * typed never reaches a path. From the answer {@link QueryScope} keeps the indices inside the grants and the link
 * and drops aliases and data streams, and this class narrows that to the prefixes the server is configured for and
 * drops indices OpenSearch flags as hidden (an exact name, unlike a wildcard, can reach one).
 *
 * <p>Nothing is kept between calls.
 */
@Component
@EnableConfigurationProperties(IndexScopeProperties.class)
public class QueryScopeIndices implements ScopedIndices {

    static final String TYPE = "OpenSearch::Index";

    /** Looked up per call: the policy needs the resolvers, and the resolver of the index needs this class. */
    private final Supplier<QueryScope> scopes;
    private final OpenSearchPort openSearch;
    private final IndexScope configured;
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    public QueryScopeIndices(ObjectProvider<ResourcePolicy> policy, OpenSearchPort openSearch,
            IndexScopeProperties properties) {
        this(() -> policy.getObject().queryScope(TYPE), openSearch, properties);
    }

    QueryScopeIndices(Supplier<QueryScope> scopes, OpenSearchPort openSearch, IndexScopeProperties properties) {
        this.scopes = scopes;
        this.openSearch = openSearch;
        this.configured = new IndexScope(properties.allowedIndexPrefixes());
    }

    @Override
    public List<String> names() {
        QueryScope scope = scopes.get();
        if (scope.nothingToQuery()) {
            return List.of();
        }
        String response = openSearch.resolveIndexResponse(scope.opensearchIndexExpression());
        // Empty means "do not ask": a request with no index in its path would search every index.
        Optional<String> target = scope.opensearchSearchTarget(response);
        if (target.isEmpty()) {
            return List.of();
        }
        Set<String> hidden = hiddenIndices(response);
        return Arrays.stream(target.get().split(","))
                .filter(configured::covers)
                .filter(name -> !hidden.contains(name))
                .toList();
    }

    @Override
    public Optional<String> argument(String index) {
        QueryScope scope = scopes.get();
        if (scope.nothingToQuery()) {
            return Optional.empty();
        }
        String response = openSearch.resolveIndexResponse(scope.opensearchIndexExpression());
        String name;
        try {
            name = scope.resolveIndexArgument(index, response);
        } catch (SanitizedToolException notFoundOrDenied) {
            return Optional.empty();
        }
        return configured.covers(name) && !hiddenIndices(response).contains(name) ? Optional.of(name) : Optional.empty();
    }

    private Set<String> hiddenIndices(String resolveIndexResponse) {
        Set<String> hidden = new HashSet<>();
        for (JsonNode index : json.readTree(resolveIndexResponse).path("indices")) {
            for (JsonNode attribute : index.path("attributes")) {
                if ("hidden".equals(attribute.asString(""))) {
                    hidden.add(index.path("name").asString(""));
                }
            }
        }
        return hidden;
    }
}
