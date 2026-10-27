package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import pl.aiwcorpo.mcp.opensearch.core.domain.OpensearchRecord;
import pl.aiwcorpo.mcp.opensearch.core.port.in.OpensearchUseCase;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;

/**
 * The MCP tools of this server. Each public {@code @Tool} method is a capability an AI client can call
 * through the gateway, so every argument is untrusted input from a model.
 *
 * <p>The gateway decides who may call a tool and asks for confirmations; the starter verifies that the
 * call came from the gateway for exactly this tool. What stays here: a risk flag on every tool, strict
 * argument checks, and the resource always named by an explicit argument.
 */
@Service
public class OpensearchTool {

    /** Record ids as the backend issues them; anything else never reaches the backend URL. */
    private static final Pattern RECORD_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private final OpensearchUseCase useCase;

    public OpensearchTool(OpensearchUseCase useCase) {
        this.useCase = useCase;
    }

    @Tool(name = "get_record", description = "Returns one record by its id: id, project, title and status.")
    @ToolRisk(RiskLevel.READ_ONLY)
    public OpensearchRecord getRecord(
            @ToolParam(description = "Record id: letters, digits, '-' or '_', at most 64 characters") String recordId) {
        return useCase.getRecord(validRecordId(recordId));
    }

    private static String validRecordId(String recordId) {
        if (recordId == null || !RECORD_ID.matcher(recordId).matches()) {
            throw new InvalidToolArgumentException("recordId must be 1-64 characters: letters, digits, '-' or '_'");
        }
        return recordId;
    }
}
