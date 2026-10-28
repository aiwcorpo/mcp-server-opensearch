package pl.aiwcorpo.mcp.opensearch.core.port.out;

import java.util.List;

/**
 * Classification of an index for the policy ({@code OpenSearch::Index.tags}). OpenSearch has no tags, so they come
 * from the server configuration; an index with none gets an empty list, which is a statement, not a missing value.
 */
public interface IndexClassifier {

    List<String> tagsOf(String index);
}
