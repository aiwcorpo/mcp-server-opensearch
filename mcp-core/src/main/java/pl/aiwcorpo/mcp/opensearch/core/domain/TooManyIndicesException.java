package pl.aiwcorpo.mcp.opensearch.core.domain;

/** A request would have to name more indices than the server allows: the names travel in the request path. */
public class TooManyIndicesException extends RuntimeException {

    public TooManyIndicesException() {
        super("too many indices", null, false, false);
    }
}
