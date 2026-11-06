package pl.aiwcorpo.mcp.opensearch.core.domain;

/**
 * The index as the platform declares it in {@code mcp/policies/resource-types.json}. The two patterns are written there
 * as {@code idPattern} and {@code scopePattern}; they are repeated here only for the prefix argument of the search
 * tools, which names a set of indices and so is not a {@code @ResourceRef}. {@code ResourceDeclarationsTest} in mcp-app
 * keeps both copies equal.
 */
public final class OpenSearchResourceModel {

    public static final String INDEX_TYPE = "OpenSearch::Index";

    /**
     * One concrete index name: lower case, starting with a letter or digit so that hidden and system indices
     * ({@code .security}, {@code .kibana}) are out, at most 255 characters.
     */
    public static final String INDEX_NAME_REGEX = "^[a-z0-9][a-z0-9._-]{0,254}$";

    /** A grantable prefix: an index name that does not end with {@code -} or {@code .}. */
    public static final String INDEX_PREFIX_REGEX = "^(?:[a-z0-9][a-z0-9._-]{0,253}[a-z0-9_]|[a-z0-9])$";

    public static final int INDEX_NAME_MAX_LENGTH = 255;

    private OpenSearchResourceModel() {
    }
}
