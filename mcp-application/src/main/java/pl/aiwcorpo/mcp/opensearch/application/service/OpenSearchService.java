package pl.aiwcorpo.mcp.opensearch.application.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexOutsideScopeException;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.opensearch.core.domain.ResolvedIndices;
import pl.aiwcorpo.mcp.opensearch.core.domain.UnsupportedIndexTargetException;
import pl.aiwcorpo.mcp.opensearch.core.port.in.OpenSearchUseCase;
import pl.aiwcorpo.mcp.opensearch.core.port.out.IndexScopeProvider;
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpenSearchPort;

/**
 * Use cases of the server. A plain class: mcp-app makes it a bean, so this module stays free of Spring.
 *
 * <p>The name in the arguments is only the first check. An index expression can match aliases, and an alias
 * can point anywhere, so every expression is resolved first and every index behind it must be covered by the
 * scope; then the search itself is restricted to the scope again and hits outside it are dropped.
 *
 * <p>{@code _resolve/index} does not say whether the index behind an alias is hidden, so the targets of every
 * alias are resolved once more by name (where the {@code hidden} attribute is reported). A document read goes
 * to the concrete index and would skip the filter or routing of an alias, so aliases that have either are not
 * read.
 */
public class OpenSearchService implements OpenSearchUseCase {

    private final OpenSearchPort openSearch;
    private final IndexScopeProvider scopes;

    public OpenSearchService(OpenSearchPort openSearch, IndexScopeProvider scopes) {
        this.openSearch = openSearch;
        this.scopes = scopes;
    }

    @Override
    public List<LogHit> searchLogs(LogQuery query) {
        IndexScope scope = scopes.current();
        String expression = scope.expressionFor(query.pattern()).orElseThrow(IndexOutsideScopeException::new);
        ResolvedIndices resolved = openSearch.resolve(expression).orElseThrow(NotFoundOrDeniedException::new);
        requireInsideScope(resolved, scope);
        if (resolved.isEmpty()) {
            // A named index that is missing must look like one the caller may not read (403 on resolve).
            if (!query.pattern().wildcard()) {
                throw new NotFoundOrDeniedException();
            }
            return List.of();
        }
        return openSearch.search(expression, scope, query).stream()
                .filter(hit -> scope.covers(hit.index()))
                .toList();
    }

    @Override
    public IndexDocument getDocument(String index, String id) {
        IndexScope scope = scopes.current();
        if (!scope.covers(index)) {
            throw new IndexOutsideScopeException();
        }
        ResolvedIndices resolved = openSearch.resolve(index).orElseThrow(NotFoundOrDeniedException::new);
        requireInsideScope(resolved, scope);
        String concrete = concreteIndex(resolved);
        if (!resolved.aliases().isEmpty() && openSearch.aliasHasFilterOrRouting(resolved.aliases().getFirst().name())) {
            throw new NotFoundOrDeniedException();
        }
        // The concrete index, not the alias: the alias could be repointed between the check and the read.
        IndexDocument document = openSearch.findDocument(concrete, id).orElseThrow(NotFoundOrDeniedException::new);
        if (!scope.covers(document.index())) {
            throw new NotFoundOrDeniedException();
        }
        return document;
    }

    private void requireInsideScope(ResolvedIndices resolved, IndexScope scope) {
        if (!resolved.dataStreams().isEmpty()) {
            throw new UnsupportedIndexTargetException();
        }
        for (ResolvedIndices.Index index : resolved.indices()) {
            if (index.hidden() || !scope.covers(index.name())) {
                throw new NotFoundOrDeniedException();
            }
        }
        for (ResolvedIndices.Alias alias : resolved.aliases()) {
            if (!scope.covers(alias.name()) || alias.indices().isEmpty()
                    || !alias.indices().stream().allMatch(scope::covers)) {
                throw new NotFoundOrDeniedException();
            }
        }
        requireVisibleAliasTargets(resolved);
    }

    private void requireVisibleAliasTargets(ResolvedIndices resolved) {
        Set<String> targets = new LinkedHashSet<>();
        for (ResolvedIndices.Alias alias : resolved.aliases()) {
            targets.addAll(alias.indices());
        }
        resolved.indices().forEach(index -> targets.remove(index.name()));
        if (targets.isEmpty()) {
            return;
        }
        ResolvedIndices byName = openSearch.resolve(String.join(",", targets)).orElseThrow(NotFoundOrDeniedException::new);
        Set<String> visible = new LinkedHashSet<>();
        for (ResolvedIndices.Index index : byName.indices()) {
            if (index.hidden()) {
                throw new NotFoundOrDeniedException();
            }
            visible.add(index.name());
        }
        if (!visible.containsAll(targets)) {
            throw new NotFoundOrDeniedException();
        }
    }

    private static String concreteIndex(ResolvedIndices resolved) {
        if (resolved.isEmpty()) {
            throw new NotFoundOrDeniedException();
        }
        if (resolved.aliases().isEmpty() && resolved.indices().size() == 1) {
            return resolved.indices().getFirst().name();
        }
        if (resolved.indices().isEmpty() && resolved.aliases().size() == 1
                && resolved.aliases().getFirst().indices().size() == 1) {
            return resolved.aliases().getFirst().indices().getFirst();
        }
        throw new UnsupportedIndexTargetException();
    }
}
