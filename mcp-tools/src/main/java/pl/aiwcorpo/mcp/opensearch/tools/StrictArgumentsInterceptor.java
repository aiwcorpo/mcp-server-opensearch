package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.Map;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.observability.ToolCallInterceptor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code additionalProperties: false} on the server side. Spring AI binds only the parameters a method declares
 * and silently ignores the rest, so a call with {@code {"index":"x","q":"..."}} would otherwise look accepted.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StrictArgumentsInterceptor implements ToolCallInterceptor {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String intercept(Invocation invocation, Next next) {
        Set<String> allowed = OpenSearchToolSchemas.argumentsOf(invocation.tool());
        String arguments = invocation.arguments();
        if (arguments != null && !arguments.isBlank()) {
            JsonNode node;
            try {
                node = JSON.readTree(arguments);
            } catch (JacksonException ex) {
                throw new InvalidToolArgumentException("arguments must be a JSON object");
            }
            if (!node.isObject()) {
                throw new InvalidToolArgumentException("arguments must be a JSON object");
            }
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (!allowed.contains(property.getKey())) {
                    throw new InvalidToolArgumentException("arguments not declared in the input schema are not accepted");
                }
            }
        }
        return next.proceed(arguments);
    }
}
