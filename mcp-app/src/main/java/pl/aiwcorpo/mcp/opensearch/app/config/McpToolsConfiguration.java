package pl.aiwcorpo.mcp.opensearch.app.config;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.aiwcorpo.mcp.opensearch.tools.OpenSearchToolSchemas;
import pl.aiwcorpo.mcp.opensearch.tools.OpenSearchTools;

/**
 * The tool set of the server, as an explicit list: the MCP server exposes only tools registered through a
 * {@link ToolCallbackProvider} (an {@code @Tool} bean alone answers "Unknown tool"), and a reviewed list
 * keeps the public surface from growing by accident.
 *
 * <p>Name and description come from {@code @Tool}; the input schema is the hand-written one from
 * {@link OpenSearchToolSchemas} (limits, patterns, {@code additionalProperties: false}). The callbacks stay
 * {@link MethodToolCallback}s, which is what the starter reads {@code @ToolRisk} from.
 */
@Configuration
public class McpToolsConfiguration {

    @Bean
    ToolCallbackProvider mcpTools(OpenSearchTools openSearchTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        List<Method> methods = new ArrayList<>(List.of(OpenSearchTools.class.getDeclaredMethods()));
        methods.sort(Comparator.comparing(Method::getName));
        for (Method method : methods) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            String name = ToolUtils.getToolName(method);
            callbacks.add(MethodToolCallback.builder()
                    .toolDefinition(ToolDefinitions.builder(method)
                            .inputSchema(OpenSearchToolSchemas.schemaOf(name))
                            .build())
                    .toolMetadata(ToolMetadata.from(method))
                    .toolMethod(method)
                    .toolObject(openSearchTools)
                    .build());
        }
        return ToolCallbackProvider.from(callbacks);
    }
}
