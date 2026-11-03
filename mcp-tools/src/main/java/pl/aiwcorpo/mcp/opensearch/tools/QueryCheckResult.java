package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.List;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

/** Result of {@code validate_query}; the explanation is built from the caller's own words, so it is untrusted text. */
public record QueryCheckResult(boolean valid, int checked, List<String> invalidIndices, UntrustedText explanation) {
}
