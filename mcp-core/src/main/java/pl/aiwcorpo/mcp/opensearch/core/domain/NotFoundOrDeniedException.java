package pl.aiwcorpo.mcp.opensearch.core.domain;

/**
 * The index or document does not exist, the caller may not see it, or an alias leads outside the allowed
 * prefixes. One exception on purpose: the client gets the same {@code not_found_or_denied} for all of them,
 * so it cannot probe which indices, aliases or documents exist.
 */
public class NotFoundOrDeniedException extends RuntimeException {

    public NotFoundOrDeniedException() {
        super("index or document not found or not visible", null, false, false);
    }
}
