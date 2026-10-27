package pl.aiwcorpo.mcp.opensearch.core.domain;

/**
 * The expression resolves to something the server does not handle yet: a data stream (its backing indices are
 * hidden {@code .ds-*} indices, which the prefix check rejects) or an alias of several indices for
 * {@code get_document}.
 */
public class UnsupportedIndexTargetException extends RuntimeException {

    public UnsupportedIndexTargetException() {
        super("unsupported index target", null, false, false);
    }
}
