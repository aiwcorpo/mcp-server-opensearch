package pl.aiwcorpo.mcp.opensearch.core.domain;

/** The index or pattern named in the arguments does not start with any allowed prefix. */
public class IndexOutsideScopeException extends RuntimeException {

    public IndexOutsideScopeException() {
        super("index outside the allowed prefixes", null, false, false);
    }
}
