package pl.aiwcorpo.mcp.opensearch.app.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.aiwcorpo.mcp.opensearch.tools.OpenSearchTools;
import pl.aiwcorpo.mcp.platform.input.SchemaLimitedToolCallbacks;

/**
 * The tool set of the server, as an explicit list: the MCP server exposes only tools registered through a
 * {@link ToolCallbackProvider} (an {@code @Tool} bean alone answers "Unknown tool"), and a reviewed list
 * keeps the public surface from growing by accident. {@link SchemaLimitedToolCallbacks} adds the
 * {@code @ArgumentLimits} of the parameters and {@code additionalProperties: false} to each input schema; the
 * starter enforces that schema on every call.
 */
@Configuration
public class McpToolsConfiguration {

    @Bean
    public ToolCallbackProvider mcpTools(OpenSearchTools openSearchTools) {
        return SchemaLimitedToolCallbacks.of(openSearchTools);
    }
}
