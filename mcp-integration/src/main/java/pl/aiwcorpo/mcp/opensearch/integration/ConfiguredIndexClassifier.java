package pl.aiwcorpo.mcp.opensearch.integration;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.opensearch.core.port.out.IndexClassifier;

/** Tags of an index: the union of the tags of every configured prefix that covers it. */
@Component
@EnableConfigurationProperties(IndexScopeProperties.class)
public class ConfiguredIndexClassifier implements IndexClassifier {

    private final List<IndexScopeProperties.IndexTags> rules;

    public ConfiguredIndexClassifier(IndexScopeProperties properties) {
        this.rules = properties.indexTags();
    }

    @Override
    public List<String> tagsOf(String index) {
        Set<String> tags = new LinkedHashSet<>();
        for (IndexScopeProperties.IndexTags rule : rules) {
            if (rule.covers(index)) {
                tags.addAll(rule.tags());
            }
        }
        return List.copyOf(tags);
    }
}
