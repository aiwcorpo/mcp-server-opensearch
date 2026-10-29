package pl.aiwcorpo.mcp.opensearch.core.port.out;

import java.util.List;
import java.util.Optional;

/**
 * The concrete indices the current call may query, resolved against OpenSearch with the caller's credential. The
 * answer is never kept between calls: a cached one would widen the window in which an index could be replaced by
 * an alias of the same name.
 *
 * <p>Aliases and data streams are never part of it, whatever their name. A query goes to these names and never to
 * a pattern.
 */
public interface ScopedIndices {

    /**
     * Sorted names of the open, visible, not hidden indices inside the caller's scope and the prefixes this
     * server is configured for. Empty when there is nothing to query; OpenSearch is not asked at all when the
     * scope excludes everything.
     */
    List<String> names();

    /**
     * The index named by a tool argument, when it is one of {@link #names()}. Empty for an alias, a data stream,
     * an index outside the scope and one OpenSearch does not show: the cause is not told apart.
     */
    Optional<String> argument(String index);
}
