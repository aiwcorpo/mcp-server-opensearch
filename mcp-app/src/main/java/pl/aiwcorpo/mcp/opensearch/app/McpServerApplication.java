package pl.aiwcorpo.mcp.opensearch.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. Scans only this server's packages: the starter's beans arrive through auto-configuration,
 * and scanning {@code pl.aiwcorpo.mcp} as a whole would pick them up a second time.
 */
@SpringBootApplication(scanBasePackages = "pl.aiwcorpo.mcp.opensearch")
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
