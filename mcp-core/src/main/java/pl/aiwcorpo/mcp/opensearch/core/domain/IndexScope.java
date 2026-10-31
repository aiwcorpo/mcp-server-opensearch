package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.List;

/**
 * The index prefixes a call may touch. A prefix {@code p} covers the index named exactly {@code p} and every
 * index named {@code p-...}, never {@code p...} without the dash: {@code logs-team-a} does not cover
 * {@code logs-team-ab} (the same rule as {@code OpenSearchIndexPrefix} in mcp-contracts and the Cedar scopes).
 *
 * <p>The prefixes are the grants of the caller's groups, within the prefixes the server is configured for
 * ({@code IndexScopeProvider}). No prefix at all is an empty scope that covers nothing: it never stands for
 * "every index".
 */
public record IndexScope(List<String> prefixes) {

    public IndexScope {
        prefixes = List.copyOf(prefixes);
    }

    public boolean isEmpty() {
        return prefixes.isEmpty();
    }

    public boolean covers(String index) {
        if (index == null || index.startsWith(".")) {
            return false;
        }
        return prefixes.stream().anyMatch(prefix -> index.equals(prefix) || index.startsWith(prefix + "-"));
    }
}
