package pl.aiwcorpo.mcp.opensearch.core.domain;

/** An aggregation would have to name more indices than the server allows in one request. */
public class TooManyIndicesException extends RuntimeException {

    public TooManyIndicesException() {
        super("too many indices", null, false, false);
    }
}
