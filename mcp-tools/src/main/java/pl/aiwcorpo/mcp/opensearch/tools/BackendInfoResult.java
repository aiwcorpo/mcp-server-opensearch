package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Result of {@code get_backend_info}: the capability state the platform keeps ({@code supported}, {@code degraded} or
 * {@code unavailable}), sanitized by the platform before it gets here.
 */
public record BackendInfoResult(String state, @Nullable String version, List<String> features, String detail) {
}
