package pl.aiwcorpo.mcp.opensearch.integration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.opensearch.core.port.out.IndexScopeProvider;
import pl.aiwcorpo.mcp.platform.resource.QueryScope;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;

/**
 * The scope of the current call: what the caller's {@code mcp-opensearch-idx-*} groups grant ({@link QueryScope}),
 * within the prefixes this server is configured for. OpenSearch enforces the user's rights on its own, but a grant
 * is narrower than those, so the query is limited to it.
 *
 * <p>When nothing is granted the scope is empty and the service refuses; an empty index expression would search
 * every index, which is why {@link QueryScope#nothingToQuery()} is asked first.
 */
@Component
@EnableConfigurationProperties(IndexScopeProperties.class)
public class QueryScopeIndexScopeProvider implements IndexScopeProvider {

    static final String TYPE = "OpenSearch::Index";

    /** Looked up per call: the policy needs the resolvers, and the resolver of the index needs this scope. */
    private final ObjectProvider<ResourcePolicy> policy;
    private final List<String> configured;

    public QueryScopeIndexScopeProvider(ObjectProvider<ResourcePolicy> policy, IndexScopeProperties properties) {
        this.policy = policy;
        this.configured = properties.allowedIndexPrefixes();
    }

    @Override
    public IndexScope current() {
        return scopeOf(policy.getObject().queryScope(TYPE), configured);
    }

    static IndexScope scopeOf(QueryScope scope, List<String> configured) {
        if (scope.nothingToQuery()) {
            return new IndexScope(List.of());
        }
        return new IndexScope(withinConfigured(prefixesOf(scope.opensearchIndexExpression()), configured));
    }

    /** The expression is a list of {@code p} and {@code p-*} pairs; the prefixes are the entries without the wildcard. */
    static List<String> prefixesOf(String expression) {
        List<String> prefixes = new ArrayList<>();
        for (String entry : expression.split(",")) {
            if (!entry.endsWith("*")) {
                prefixes.add(entry);
            }
        }
        return prefixes;
    }

    /**
     * Keeps a granted prefix the server is configured for, or a configured one inside it (the grant of
     * {@code logs} with the server configured for {@code logs-team-a} leaves {@code logs-team-a}).
     */
    static List<String> withinConfigured(List<String> granted, List<String> configured) {
        Set<String> kept = new LinkedHashSet<>();
        for (String grant : granted) {
            for (String allowed : configured) {
                if (grant.equals(allowed) || grant.startsWith(allowed + "-")) {
                    kept.add(grant);
                } else if (allowed.startsWith(grant + "-")) {
                    kept.add(allowed);
                }
            }
        }
        return List.copyOf(kept);
    }
}
