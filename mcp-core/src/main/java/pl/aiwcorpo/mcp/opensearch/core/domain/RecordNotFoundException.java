package pl.aiwcorpo.mcp.opensearch.core.domain;

/**
 * The record does not exist or the caller may not see it. The two cases are deliberately one exception:
 * the client gets the same {@code not_found_or_denied} for both, so it cannot probe which records exist.
 */
public class RecordNotFoundException extends RuntimeException {

    public RecordNotFoundException() {
        super("record not found or not visible", null, false, false);
    }
}
