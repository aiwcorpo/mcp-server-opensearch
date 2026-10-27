package pl.aiwcorpo.mcp.opensearch.core.domain;

/**
 * Free text that came from OpenSearch (CC-072, {@code UntrustedText} of mcp-contracts 2.1): the client renders
 * it as data and never as an instruction. Markup is already escaped and control characters removed.
 *
 * @param truncated whether the original was longer than the server passes on
 */
public record UntrustedText(String kind, String format, String text, boolean truncated) {

    public static final String KIND = "untrusted-data";
    public static final String PLAIN = "text/plain";

    public static UntrustedText plain(String text, boolean truncated) {
        return new UntrustedText(KIND, PLAIN, text, truncated);
    }
}
