package pl.aiwcorpo.mcp.opensearch.integration;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Index prefixes this server may touch at all ({@code MCP_OPENSEARCH_INDEX_PREFIXES}) and the tags it gives to some
 * of them. Without any prefix the server does not start: an empty list would either allow nothing or tempt someone to
 * read it as "everything". The caller's own scope is the intersection of these prefixes with the grants of the
 * policy bundle.
 *
 * @param allowedIndexPrefixes grantable prefixes in the {@code OpenSearchIndexPrefix} form of mcp-contracts,
 *                             additionally not starting with {@code .} (hidden and system indices)
 * @param indexTags            classification of indices by prefix, for the policy ({@code restricted} closes them)
 */
@ConfigurationProperties("opensearch.scope")
public record IndexScopeProperties(List<String> allowedIndexPrefixes, List<IndexTags> indexTags) {

    /** {@code OpenSearchIndexPrefix} of mcp-contracts 2.0.2 without the leading dot. */
    private static final Pattern PREFIX = Pattern.compile("[a-z0-9][a-z0-9._-]{0,253}[a-z0-9_]|[a-z0-9]");
    private static final Pattern TAG = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    /**
     * @param prefix the tags apply to the index named exactly like it and to every index starting with
     *               {@code <prefix>-}, as everywhere else in this server
     */
    public record IndexTags(String prefix, List<String> tags) {

        public IndexTags {
            tags = tags == null ? List.of() : tags.stream().map(String::strip).filter(t -> !t.isEmpty()).toList();
            if (prefix == null || !PREFIX.matcher(prefix.strip()).matches()) {
                throw new IllegalStateException("opensearch.scope.index-tags: '" + prefix + "' is not a prefix");
            }
            prefix = prefix.strip();
            for (String tag : tags) {
                if (!TAG.matcher(tag).matches()) {
                    throw new IllegalStateException("opensearch.scope.index-tags: a tag must be lower case letters,"
                            + " digits, '_' or '-' (at most 32 characters)");
                }
            }
        }

        boolean covers(String index) {
            return index.equals(prefix) || index.startsWith(prefix + "-");
        }
    }

    public IndexScopeProperties {
        allowedIndexPrefixes = allowedIndexPrefixes == null ? List.of()
                : allowedIndexPrefixes.stream().map(String::strip).filter(p -> !p.isEmpty()).toList();
        indexTags = indexTags == null ? List.of() : List.copyOf(indexTags);
        if (allowedIndexPrefixes.isEmpty()) {
            throw new IllegalStateException(
                    "opensearch.scope.allowed-index-prefixes (MCP_OPENSEARCH_INDEX_PREFIXES) must list at least one prefix");
        }
        for (String prefix : allowedIndexPrefixes) {
            if (!PREFIX.matcher(prefix).matches()) {
                throw new IllegalStateException("opensearch.scope.allowed-index-prefixes: '" + prefix
                        + "' is not a grantable prefix (lower case, not starting with . _ or -, not ending with - or .)");
            }
        }
    }
}
