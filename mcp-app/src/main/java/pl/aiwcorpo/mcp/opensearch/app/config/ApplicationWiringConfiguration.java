package pl.aiwcorpo.mcp.opensearch.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.aiwcorpo.mcp.opensearch.application.service.OpensearchService;
import pl.aiwcorpo.mcp.opensearch.core.port.in.OpensearchUseCase;
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpensearchResourcePort;

/** Assembles the hexagon: framework-free use cases from mcp-application, adapters from mcp-integration. */
@Configuration
public class ApplicationWiringConfiguration {

    @Bean
    OpensearchUseCase opensearchUseCase(OpensearchResourcePort resource) {
        return new OpensearchService(resource);
    }
}
