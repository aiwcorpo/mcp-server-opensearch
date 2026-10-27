package pl.aiwcorpo.mcp.opensearch.integration;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Index prefixes this server may touch at all ({@code MCP_OPENSEARCH_INDEX_PREFIXES}). Without any the server
 * does not start: an empty list would either allow nothing or tempt someone to read it as "everything".
 *
 * @param allowedIndexPrefixes grantable prefixes in the {@code OpenSearchIndexPrefix} form of mcp-contracts,
 *                             additionally not starting with {@code .} (hidden and system indices)
 */
@ConfigurationProperties("opensearch.scope")
public record IndexScopeProperties(List<String> allowedIndexPrefixes) {

    /** {@code OpenSearchIndexPrefix} of mcp-contracts 2.0.2 without the leading dot. */
    private static final Pattern PREFIX = Pattern.compile("[a-z0-9][a-z0-9._-]{0,253}[a-z0-9_]|[a-z0-9]");

    public IndexScopeProperties {
        allowedIndexPrefixes = allowedIndexPrefixes == null ? List.of()
                : allowedIndexPrefixes.stream().map(String::strip).filter(p -> !p.isEmpty()).toList();
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
