package pl.aiwcorpo.mcp.opensearch.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.aiwcorpo.mcp.opensearch.application.service.OpenSearchService;
import pl.aiwcorpo.mcp.opensearch.core.domain.SearchLimits;
import pl.aiwcorpo.mcp.opensearch.core.port.in.OpenSearchUseCase;
import pl.aiwcorpo.mcp.opensearch.core.port.out.IndexScopeProvider;
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpenSearchPort;
import pl.aiwcorpo.mcp.opensearch.integration.OpenSearchSearchProperties;

/** Assembles the hexagon: framework-free use cases from mcp-application, adapters from mcp-integration. */
@Configuration
public class ApplicationWiringConfiguration {

    @Bean
    OpenSearchUseCase openSearchUseCase(OpenSearchPort openSearch, IndexScopeProvider scopes) {
        return new OpenSearchService(openSearch, scopes);
    }

    @Bean
    SearchLimits searchLimits(OpenSearchSearchProperties search) {
        return new SearchLimits(search.maxRange());
    }
}
