package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.List;
import org.jspecify.annotations.Nullable;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogField;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.platform.resource.ResourceRef;

/**
 * One hit of {@code search_logs}. The index is a {@code @ResourceRef}: after the tool ran, the starter resolves the
 * index of every hit and drops the hits of indices the policy refuses (a {@code restricted} tag), so a list never
 * shows more than {@code get_document} would.
 */
public record LogHitView(@ResourceRef(type = OpenSearchIndexResolver.TYPE) String index, @Nullable String id,
        @Nullable String timestamp, List<LogField> data, boolean truncated) {

    static LogHitView of(LogHit hit) {
        return new LogHitView(hit.index(), hit.id(), hit.timestamp(), hit.data(), hit.truncated());
    }
}
