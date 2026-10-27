package pl.aiwcorpo.mcp.opensearch.core.domain;

/**
 * One index pattern of {@code search_logs} after syntax checks: a name part and at most one trailing
 * {@code *}. Lists, exclusions, date math and remote clusters never get this far.
 *
 * @param base     the part before the wildcard, {@code [a-z0-9-]+}, not starting with {@code -}
 * @param wildcard whether the pattern ended with {@code *}
 */
public record IndexPattern(String base, boolean wildcard) {

    @Override
    public String toString() {
        return wildcard ? base + "*" : base;
    }
}
