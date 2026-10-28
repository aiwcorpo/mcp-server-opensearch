package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.Optional;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.opensearch.core.domain.AggregationOverAliasException;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexOutsideScopeException;
import pl.aiwcorpo.mcp.opensearch.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.opensearch.core.domain.TooManyIndicesException;
import pl.aiwcorpo.mcp.opensearch.core.domain.UnsupportedIndexTargetException;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper.SafeError;
import pl.aiwcorpo.mcp.platform.error.ToolErrorCode;
import pl.aiwcorpo.mcp.platform.error.ToolErrorMapping;

/** Domain exceptions of this server as C10 errors; everything else falls through to the starter's defaults. */
@Component
public class OpenSearchToolErrors implements ToolErrorMapping {

    @Override
    public Optional<SafeError> map(Throwable ex) {
        // An index outside the caller's grants answers like a missing or forbidden one: the name is well formed,
        // and which prefixes a caller may use is not something to tell them.
        if (ex instanceof NotFoundOrDeniedException || ex instanceof IndexOutsideScopeException) {
            return Optional.of(new SafeError(ToolErrorCode.NOT_FOUND_OR_DENIED.code(),
                    "The resource does not exist or you do not have access to it."));
        }
        if (ex instanceof UnsupportedIndexTargetException) {
            return Optional.of(new SafeError(ToolErrorCode.INVALID_ARGUMENT.code(),
                    "The index pattern reaches a data stream; this server does not read those."));
        }
        if (ex instanceof AggregationOverAliasException) {
            return Optional.of(new SafeError(ToolErrorCode.INVALID_ARGUMENT.code(),
                    "The index pattern reaches an alias; aggregations run on concrete indices only."));
        }
        if (ex instanceof TooManyIndicesException) {
            return Optional.of(new SafeError(ToolErrorCode.INVALID_ARGUMENT.code(),
                    "The index pattern covers too many indices for one aggregation; use a narrower pattern."));
        }
        return Optional.empty();
    }
}
