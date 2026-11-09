package pl.aiwcorpo.mcp.opensearch.app;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import pl.aiwcorpo.mcp.opensearch.app.config.McpToolsConfiguration;
import pl.aiwcorpo.mcp.opensearch.core.domain.SearchLimits;
import pl.aiwcorpo.mcp.opensearch.tools.OpenSearchTools;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.guard.ToolHintsPostProcessor;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.manifest.ManifestGenerator;
import pl.aiwcorpo.mcp.platform.policy.ServerPolicies;

/**
 * Writes the C6 manifest of this server at build time (maven phase {@code package}, see the pom), from the production
 * configuration ({@code application.yml}, no profile) and the tools as registered in {@link McpToolsConfiguration}.
 * The file is the document that the running server serves at {@code GET /.well-known/mcp-manifest} and the gateway pulls
 * (CC-249), kept as a build artifact so that a reviewer can read what the commit publishes.
 *
 * <p>It does not start the server: the base configuration needs the JWKS address of the gateway and a certificate or a state
 * directory. It runs the same {@link ManifestGenerator} with the same checks as the server at startup, so a manifest the gateway
 * would reject stops the build. {@code ManifestTest} compares the tools of this file with {@code tools/list} of the
 * running server. Usage: {@code ManifestExport <output file>}.
 */
public final class ManifestExport {

    private ManifestExport() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: ManifestExport <output file>");
        }
        write(Path.of(args[0]));
    }

    static void write(Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Files.writeString(output, manifest() + "\n", StandardCharsets.UTF_8);
    }

    static String manifest() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        environment.getPropertySources().addLast(documents.getFirst());
        McpPlatformProperties properties = Binder.get(environment)
                .bindOrCreate("aiwcorpo.mcp.platform", McpPlatformProperties.class);

        ToolRiskRegistry registry = new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL);
        List<McpServerFeatures.SyncToolSpecification> specifications = new ArrayList<>();
        for (ToolCallback callback : new McpToolsConfiguration().mcpTools(new OpenSearchTools(null, new SearchLimits(Duration.ofDays(1), Duration.ofSeconds(20), "message"), null, null, null, null)).getToolCallbacks()) {
            registry.register(callback);
            specifications.add(McpToolUtils.toSyncToolSpecification(callback));
        }
        // The same step that adds title, hints and output schema to the list the running server publishes.
        ToolHintsPostProcessor hints = new ToolHintsPostProcessor(
                new StaticListableBeanFactory(Map.of("toolRiskRegistry", registry)).getBeanProvider(ToolRiskRegistry.class));
        List<McpSchema.Tool> published = ((List<?>) hints.postProcessAfterInitialization(specifications, "tools")).stream()
                .map(item -> ((McpServerFeatures.SyncToolSpecification) item).tool())
                .toList();
        // Read from the jar of this module, like the running server does: the policies and the declarations are part of
        // what the gateway approves, so the manifest must carry exactly these files.
        ServerPolicies policies = ServerPolicies.load(ManifestExport.class.getClassLoader());
        return ManifestGenerator.generate(properties, registry, published, policies, true);
    }
}
