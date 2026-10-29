package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.Optional;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.opensearch.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.opensearch.core.domain.TooManyIndicesException;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper.SafeError;
import pl.aiwcorpo.mcp.platform.error.ToolErrorCode;
import pl.aiwcorpo.mcp.platform.error.ToolErrorMapping;

/** Domain exceptions of this server as C10 errors; everything else falls through to the starter's defaults. */
@Component
public class OpenSearchToolErrors implements ToolErrorMapping {

    @Override
    public Optional<SafeError> map(Throwable ex) {
        // The text is the platform constant: the starter ignores any other one.
        if (ex instanceof NotFoundOrDeniedException) {
            return Optional.of(new SafeError(ToolErrorCode.NOT_FOUND_OR_DENIED.code(),
                    ToolErrorCode.NOT_FOUND_OR_DENIED_MESSAGE));
        }
        if (ex instanceof TooManyIndicesException) {
            return Optional.of(new SafeError(ToolErrorCode.INVALID_ARGUMENT.code(),
                    "The index prefix covers too many indices for one request; narrow the date range (from and to)."));
        }
        return Optional.empty();
    }
}
