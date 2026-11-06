package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.regex.Pattern;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.cedar.entities.Scopes;
import pl.aiwcorpo.mcp.opensearch.core.domain.OpenSearchResourceModel;
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

    public static final String TYPE = OpenSearchResourceModel.INDEX_TYPE;

    private static final Pattern GRANTABLE_PREFIX = Pattern.compile(OpenSearchResourceModel.INDEX_PREFIX_REGEX);

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
     * The grant judged before OpenSearch is asked: the index as its name alone describes it, with no tags, which is the
     * least restrictive classification. A caller whose scope mapping reaches no prefix of the name gets
     * {@code not_found_or_denied} without a call to the cluster.
     */
    @Override
    public Optional<ResolvedResource> probe(String index) {
        return Optional.of(resource(index, List.of()));
    }

    /**
     * The index as the policy sees it. {@code tags} is required by the schema and comes from the server
     * configuration ({@code []} for an index nobody tagged), never from the argument.
     */
    ResolvedResource resource(String index) {
        return resource(index, classifier.tagsOf(index));
    }

    /**
     * An index carries the scope of every dash prefix of its name ({@code logs-team-a-2026.10} lies in {@code logs},
     * {@code logs-team}, {@code logs-team-a} and itself), so a grant of {@code logs-team-a} reaches it and one of
     * {@code logs-team-b} or {@code logs-team-ab} does not. A prefix that ends in {@code .} cannot be granted
     * ({@code scopePattern}) and is left out: the entity builder refuses any scope that does not match.
     */
    private static ResolvedResource resource(String index, List<String> tags) {
        List<String> scopes = Scopes.dashPrefixes(index).stream()
                .filter(prefix -> GRANTABLE_PREFIX.matcher(prefix).matches())
                .map(prefix -> Scopes.id(TYPE, prefix))
                .toList();
        return ResolvedResource.of(TYPE, index, Map.of("name", index, "tags", tags)).withScopes(scopes);
    }
}
