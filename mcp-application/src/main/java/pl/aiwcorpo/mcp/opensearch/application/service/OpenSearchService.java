package pl.aiwcorpo.mcp.opensearch.application.service;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import pl.aiwcorpo.mcp.opensearch.core.domain.AggregateQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogBucket;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.opensearch.core.domain.ResolvedIndex;
import pl.aiwcorpo.mcp.opensearch.core.domain.TooManyIndicesException;
import pl.aiwcorpo.mcp.opensearch.core.port.in.OpenSearchUseCase;
import pl.aiwcorpo.mcp.opensearch.core.port.out.IndexScopeProvider;
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpenSearchPort;
import pl.aiwcorpo.mcp.opensearch.core.port.out.ScopedIndices;

/**
 * Use cases of the server. A plain class: mcp-app makes it a bean, so this module stays free of Spring.
 *
 * <p>No request carries an index pattern. {@link ScopedIndices} turns the caller's scope into the names of the
 * concrete indices that exist (aliases and data streams are never among them), the prefix of the call picks from
 * those, and the request names them one by one. The query still restricts {@code _index} to the scope, and hits
 * outside it are dropped.
 *
 * <p>The policy (Cedar) is applied by the starter around these use cases: on the index of a document read (through
 * {@link #resolveIndex}, which is what the resolver of {@code OpenSearch::Index} calls), on every hit of a search,
 * and here on each index that goes into a search or an aggregation ({@code readable}).
 */
public class OpenSearchService implements OpenSearchUseCase {

    /** Indices one request may name; the request path carries them all. */
    static final int MAX_INDICES = 100;

    private final OpenSearchPort openSearch;
    private final IndexScopeProvider scopes;
    private final ScopedIndices indices;

    public OpenSearchService(OpenSearchPort openSearch, IndexScopeProvider scopes, ScopedIndices indices) {
        this.openSearch = openSearch;
        this.scopes = scopes;
        this.indices = indices;
    }

    @Override
    public List<LogHit> searchLogs(LogQuery query, Predicate<String> readable) {
        List<String> targets = targets(query.prefix(), query.from(), query.to(), readable);
        if (targets.isEmpty()) {
            return List.of();
        }
        IndexScope scope = scopes.current();
        return openSearch.search(targets, scope, query).stream()
                .filter(hit -> scope.covers(hit.index()))
                .toList();
    }

    @Override
    public List<LogBucket> aggregateLogs(AggregateQuery query, Predicate<String> readable) {
        List<String> targets = targets(query.prefix(), query.from(), query.to(), readable);
        if (targets.isEmpty()) {
            return List.of();
        }
        return openSearch.aggregate(targets, scopes.current(), query);
    }

    @Override
    public Optional<ResolvedIndex> resolveIndex(String index) {
        return indices.argument(index).map(ResolvedIndex::new);
    }

    @Override
    public Set<String> resolveIndices(Collection<String> candidates) {
        Set<String> known = new LinkedHashSet<>(indices.names());
        Set<String> accepted = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (known.contains(candidate)) {
                accepted.add(candidate);
            }
        }
        return accepted;
    }

    @Override
    public IndexDocument getDocument(String index, String id) {
        IndexScope scope = scopes.current();
        if (!scope.covers(index)) {
            throw new NotFoundOrDeniedException();
        }
        IndexDocument document = openSearch.findDocument(index, id).orElseThrow(NotFoundOrDeniedException::new);
        if (!scope.covers(document.index())) {
            throw new NotFoundOrDeniedException();
        }
        return document;
    }

    /**
     * The scope's indices under the prefix whose date (see {@link IndexDates}) meets the time range of the call, minus
     * what the policy refuses. The date only removes names from the list the scope allows; it never adds one. Empty
     * means "do not ask": a request with no index in its path would search every index.
     */
    private List<String> targets(String prefix, Instant from, Instant to, Predicate<String> readable) {
        List<String> targets = indices.names().stream()
                .filter(name -> name.equals(prefix) || name.startsWith(prefix + "-"))
                .filter(name -> IndexDates.meets(name, from, to))
                .filter(readable)
                .toList();
        if (targets.size() > MAX_INDICES) {
            throw new TooManyIndicesException();
        }
        return targets;
    }
}
