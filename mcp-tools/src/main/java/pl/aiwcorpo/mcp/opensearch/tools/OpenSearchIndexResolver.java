package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.opensearch.core.domain.ResolvedIndex;
import pl.aiwcorpo.mcp.opensearch.core.port.in.OpenSearchUseCase;
import pl.aiwcorpo.mcp.opensearch.core.port.out.IndexClassifier;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolver;

/**
 * Tells the starter what an index name is before the policy judges it: asks OpenSearch with the caller's own
 * credential, within the scope of the caller's grants, and describes the index OpenSearch names. The same resolver
 * judges the index of every hit of a search.
 *
 * <p>Empty means "not there, not visible, or not readable by this server" for every reason alike; a failure of
 * OpenSearch is an exception, which the client sees as an unavailable system and not as a denial.
 */
@Component
public class OpenSearchIndexResolver implements ResourceResolver<ResolvedIndex> {

    public static final String TYPE = "OpenSearch::Index";

    private final OpenSearchUseCase useCase;
    private final IndexClassifier classifier;

    public OpenSearchIndexResolver(OpenSearchUseCase useCase, IndexClassifier classifier) {
        this.useCase = useCase;
        this.classifier = classifier;
    }

    @Override
    public String resourceType() {
        return TYPE;
    }

    @Override
    public Optional<ResolvedIndex> fetch(String index) {
        return useCase.resolveIndex(index);
    }

    /** The hits of one search repeat the same few indices; one resolution answers for all of them. */
    @Override
    public Map<String, ResolvedIndex> fetchAll(Collection<String> indices) {
        Map<String, ResolvedIndex> fetched = new LinkedHashMap<>();
        for (String index : useCase.resolveIndices(new LinkedHashSet<>(indices))) {
            fetched.put(index, new ResolvedIndex(index));
        }
        return fetched;
    }

    @Override
    public ResolvedResource describe(ResolvedIndex index) {
        return resource(index.name());
    }

    /**
     * The index as the policy sees it. {@code tags} is required by the schema and comes from the server
     * configuration ({@code []} for an index nobody tagged), never from the argument.
     */
    ResolvedResource resource(String index) {
        return ResolvedResource.of(TYPE, index, Map.of("name", index, "tags", classifier.tagsOf(index)));
    }
}
