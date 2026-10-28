package pl.aiwcorpo.mcp.opensearch.core.domain;

/**
 * The index pattern reaches an alias. Counts cannot be corrected after the fact, and an alias can carry a filter
 * or point at indices the pattern does not name, so aggregations run on concrete indices only.
 */
public class AggregationOverAliasException extends RuntimeException {

    public AggregationOverAliasException() {
        super("aggregation over an alias", null, false, false);
    }
}
