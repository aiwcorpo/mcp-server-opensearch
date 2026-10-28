package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.List;
import org.jspecify.annotations.Nullable;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogField;

/**
 * Result of {@code get_document}: the configured fields of the document as {@link LogField} entries, whose text is
 * {@code UntrustedText}. {@code id} is null when the stored id has a shape the tool would not accept as input.
 */
public record DocumentResult(String index, @Nullable String id, List<LogField> data, boolean truncated) {
}
