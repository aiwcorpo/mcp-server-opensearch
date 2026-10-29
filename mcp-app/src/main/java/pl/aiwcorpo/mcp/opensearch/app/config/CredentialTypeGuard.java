package pl.aiwcorpo.mcp.opensearch.app.config;

import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The OpenSearch system takes the user's exchanged token and nothing else: the broker and the gateway are configured
 * the same way, and any other type would send a different credential to a server that checks the user's own rights.
 * A value from the environment or a config file that differs from {@code [token-exchange]} stops the start.
 */
@Component
public class CredentialTypeGuard implements InitializingBean {

    static final String PROPERTY = "aiwcorpo.mcp.platform.manifest.accepted-credential-types";
    static final List<String> REQUIRED = List.of("token-exchange");

    private final Environment environment;

    public CredentialTypeGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> configured = Binder.get(environment).bind(PROPERTY, Bindable.listOf(String.class)).orElse(List.of());
        if (!REQUIRED.equals(configured.stream().map(String::strip).toList())) {
            throw new IllegalStateException(PROPERTY + " must be exactly " + REQUIRED + " for the opensearch system");
        }
    }
}
