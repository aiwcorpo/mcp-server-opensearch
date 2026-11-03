package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.util.Set;

/**
 * Feature codes of the capability profile. Every call below uses an API that exists in OpenSearch 1.x, 2.x and 3.x and
 * in Elasticsearch 7.10, so the sets differ only by what the server has verified: {@link #TESTED} for 2.x and 3.x,
 * {@link #BASELINE} for any other answer of {@code GET /}.
 */
public final class OpenSearchFeatures {

    public static final String SEARCH = "search";
    public static final String COUNT = "count";
    public static final String DOCUMENT = "get_document";
    public static final String MAPPING = "mapping";
    public static final String SEARCH_AFTER = "search_after";
    public static final String LIST_INDICES = "list_indices";
    public static final String VALIDATE_QUERY = "validate_query";
    public static final String CLUSTER_HEALTH = "cluster_health";

    /** Works wherever a search endpoint compatible with Elasticsearch 7.10 answers. */
    public static final Set<String> BASELINE = Set.of(SEARCH, COUNT, DOCUMENT, MAPPING, SEARCH_AFTER);
    /** Verified against fixtures of OpenSearch 2.19 and 3.x. */
    public static final Set<String> TESTED = Set.of(SEARCH, COUNT, DOCUMENT, MAPPING, SEARCH_AFTER, LIST_INDICES,
            VALIDATE_QUERY, CLUSTER_HEALTH);

    /** Name of the probe and key in {@code BackendCapabilities}. */
    public static final String PROBE = "opensearch";

    private OpenSearchFeatures() {
    }
}
