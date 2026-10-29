package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import pl.aiwcorpo.mcp.opensearch.integration.OpenSearchAdapter;
import pl.aiwcorpo.mcp.opensearch.integration.OpenSearchBackendProperties;
import pl.aiwcorpo.mcp.opensearch.integration.OpenSearchSearchProperties;

/** What the production configuration must keep, checked without starting a server. */
class ProductionConfigurationTest {

    @Test
    void outsideDevTheServerDemandsAClientCertificate() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        PropertySource<?> production = documents.stream()
                .filter(document -> "!dev".equals(document.getProperty("spring.config.activate.on-profile")))
                .findFirst().orElseThrow();
        assertThat(String.valueOf(production.getProperty("server.ssl.client-auth"))).isEqualTo("need");
        assertThat(String.valueOf(production.getProperty("server.ssl.bundle"))).isEqualTo("mcp-server");
        assertThat(documents).allSatisfy(document -> {
            Object clientAuth = document.getProperty("server.ssl.client-auth");
            assertThat(clientAuth == null || "need".equals(String.valueOf(clientAuth))).isTrue();
        });
    }

    @Test
    void theBaseConfigurationNeedsThePolicyBundleAndTheManifestButNeverAllowsTestKeys() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        PropertySource<?> base = documents.getFirst();

        for (String key : List.of("url", "key-id", "public-key-file")) {
            assertThat(base.containsProperty("aiwcorpo.mcp.platform.policy.main." + key)).as(key).isTrue();
        }
        assertThat(base.containsProperty("aiwcorpo.mcp.platform.policy.state-dir")).isTrue();
        assertThat(base.containsProperty("aiwcorpo.mcp.platform.manifest.version")).isTrue();
        assertThat(base.containsProperty("aiwcorpo.mcp.platform.manifest.internal-url")).isTrue();
        assertThat(base.containsProperty("aiwcorpo.mcp.platform.manifest.accepted-credential-types")).isTrue();
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.containsProperty("aiwcorpo.mcp.platform.policy.allow-test-keys")).isFalse();
            assertThat(document.containsProperty("aiwcorpo.mcp.platform.internal-jwt.allow-test-keys")).isFalse();
        });
    }

    @Test
    void testKeysAndThePolicyRepositoryOfTheDevStackAreConfiguredInDevOnly() throws IOException {
        Map<String, Object> dev = (Map<String, Object>) new YamlPropertySourceLoader()
                .load("application-dev.yml", new ClassPathResource("application-dev.yml")).getFirst().getSource();

        assertThat(String.valueOf(dev.get("aiwcorpo.mcp.platform.policy.allow-test-keys"))).isEqualTo("true");
        assertThat(String.valueOf(dev.get("aiwcorpo.mcp.platform.policy.main.url"))).contains("http://127.0.0.1:");
    }

    @Test
    void outsideDevTheBackendMustBeHttps() {
        assertThatThrownBy(() -> adapter("http://backend.internal:8080", new MockEnvironment()))
                .hasMessageContaining("https");
        assertThatCode(() -> adapter("https://backend.internal", new MockEnvironment())).doesNotThrowAnyException();

        assertThatCode(() -> new OpenSearchAdapter(
                new OpenSearchBackendProperties("http://opensearch.internal:9200", null, null, null, null, true), searchDefaults(),
                new DefaultListableBeanFactory().getBeanProvider(SslBundles.class), new MockEnvironment()))
                .as("explicit opt-in for the dev environment's mocks").doesNotThrowAnyException();

        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> adapter("http://localhost:18081", dev)).doesNotThrowAnyException();
    }

    @Test
    void theBackendAddressCarriesNoUserInfoQueryOrFragment() {
        for (String baseUrl : new String[] {"https://user:secret@opensearch.internal:9200", "https://opensearch.internal:9200?x=1",
                "https://opensearch.internal:9200/#frag"}) {
            assertThatThrownBy(() -> adapter(baseUrl, new MockEnvironment()))
                    .as(baseUrl).hasMessageContaining("user info, a query or a fragment");
        }
    }

    @Test
    void devIsReachableOnLoopbackOnly() throws IOException {
        Map<String, Object> dev = (Map<String, Object>) new YamlPropertySourceLoader()
                .load("application-dev.yml", new ClassPathResource("application-dev.yml")).getFirst().getSource();
        assertThat(String.valueOf(dev.get("server.address"))).isEqualTo("127.0.0.1");
    }

    private static OpenSearchAdapter adapter(String baseUrl, MockEnvironment environment) {
        return new OpenSearchAdapter(new OpenSearchBackendProperties(baseUrl, null, null, null, null, false),
                searchDefaults(), new DefaultListableBeanFactory().getBeanProvider(SslBundles.class), environment);
    }

    private static OpenSearchSearchProperties searchDefaults() {
        return new OpenSearchSearchProperties(null, null, null, null, null, null, null);
    }

    @Test
    void onlyTokenExchangeIsAcceptedAsTheCredentialType() {
        MockEnvironment ok = new MockEnvironment().withProperty(
                "aiwcorpo.mcp.platform.manifest.accepted-credential-types", "token-exchange");
        assertThatCode(() -> new pl.aiwcorpo.mcp.opensearch.app.config.CredentialTypeGuard(ok).afterPropertiesSet())
                .doesNotThrowAnyException();
        for (String other : new String[] {"user-secret", "service-account", "token-exchange,user-secret", "user-secret,token-exchange", ""}) {
            MockEnvironment environment = new MockEnvironment().withProperty(
                    "aiwcorpo.mcp.platform.manifest.accepted-credential-types", other);
            assertThatThrownBy(() -> new pl.aiwcorpo.mcp.opensearch.app.config.CredentialTypeGuard(environment).afterPropertiesSet())
                    .as(other).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> new pl.aiwcorpo.mcp.opensearch.app.config.CredentialTypeGuard(new MockEnvironment()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theShippedConfigurationFixesTheTypeWithoutAnEnvironmentVariable() throws IOException {
        for (String file : new String[] {"application.yml", "application-dev.yml"}) {
            List<PropertySource<?>> documents = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
            assertThat(documents).anySatisfy(document -> assertThat(String.valueOf(
                    document.getProperty("aiwcorpo.mcp.platform.manifest.accepted-credential-types"))).isEqualTo("token-exchange"));
        }
    }
}
