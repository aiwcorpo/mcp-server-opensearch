package pl.aiwcorpo.mcp.opensearch.integration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.opensearch.core.port.out.IndexScopeProvider;

/**
 * M1 scope: the configured prefixes for every caller. The starter beta replaces this with the configured
 * prefixes intersected with the caller's {@code QueryScope}; nothing else in the server changes.
 */
@Component
@EnableConfigurationProperties(IndexScopeProperties.class)
public class ConfiguredIndexScopeProvider implements IndexScopeProvider {

    private final IndexScope scope;

    public ConfiguredIndexScopeProvider(IndexScopeProperties properties) {
        this.scope = new IndexScope(properties.allowedIndexPrefixes());
    }

    @Override
    public IndexScope current() {
        return scope;
    }
}
