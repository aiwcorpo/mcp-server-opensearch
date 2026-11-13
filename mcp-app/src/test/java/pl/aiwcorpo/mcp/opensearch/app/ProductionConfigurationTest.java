package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.catalina.util.ServerInfo;
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
import tools.jackson.core.Version;
import tools.jackson.core.json.PackageVersion;

/** What the production configuration must keep, checked without starting a server. */
class ProductionConfigurationTest {

    @Test
    void theServerAsksForNoClientCertificateAndLeavesTlsToTheStarter() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        // The gateway proves itself with the internal JWT, not with a certificate; the certificate of the server comes from
        // MCP_TLS_*, or the starter generates one (CC-249), so the file sets neither the client auth nor a bundle.
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.containsProperty("server.ssl.client-auth")).isFalse();
            assertThat(document.containsProperty("server.ssl.bundle")).isFalse();
            assertThat(document.containsProperty("spring.ssl.bundle.jks.mcp-server.keystore.location")).isFalse();
        });
    }

    @Test
    void theBaseConfigurationNeedsTheManifestButNeverAllowsTestKeys() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        PropertySource<?> base = documents.getFirst();

        assertThat(base.containsProperty("aiwcorpo.mcp.platform.policy-data.url")).as("pushed by the gateway").isFalse();
        assertThat(base.containsProperty("aiwcorpo.mcp.platform.manifest.version")).isTrue();
        assertThat(base.containsProperty("aiwcorpo.mcp.platform.manifest.internal-url")).as("informational and optional").isFalse();
        assertThat(base.containsProperty("aiwcorpo.mcp.platform.manifest.accepted-credential-types")).isTrue();
        assertThat(documents).allSatisfy(document -> {
            assertThat(document.containsProperty("aiwcorpo.mcp.platform.internal-jwt.allow-test-keys")).isFalse();
        });
        assertThat(documents).allSatisfy(document -> assertThat(((org.springframework.core.env.EnumerablePropertySource<?>) document)
                .getPropertyNames()).noneMatch(key -> key.startsWith("aiwcorpo.mcp.platform.policy.")));
    }

    @Test
    void testKeysAndTheLocalJwksAreConfiguredInDevOnly() throws IOException {
        Map<String, Object> dev = (Map<String, Object>) new YamlPropertySourceLoader()
                .load("application-dev.yml", new ClassPathResource("application-dev.yml")).getFirst().getSource();

        assertThat(String.valueOf(dev.get("aiwcorpo.mcp.platform.internal-jwt.allow-test-keys"))).isEqualTo("true");
        assertThat(String.valueOf(dev.get("aiwcorpo.mcp.platform.internal-jwt.jwks-uri"))).contains("http://127.0.0.1:")
                .contains("/.well-known/mcp-jwks.json");
    }

    @Test
    void outsideDevTheBackendMustBeHttps() {
        assertThatThrownBy(() -> adapter("http://backend.internal:8080", new MockEnvironment()))
                .hasMessageContaining("https");
        assertThatCode(() -> adapter("https://backend.internal", new MockEnvironment())).doesNotThrowAnyException();

        assertThatCode(() -> new OpenSearchAdapter(
                new OpenSearchBackendProperties("http://opensearch.internal:9200", null, null, null, null, true, false), searchDefaults(),
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
        return new OpenSearchAdapter(new OpenSearchBackendProperties(baseUrl, null, null, null, null, false, false),
                searchDefaults(), new DefaultListableBeanFactory().getBeanProvider(SslBundles.class), environment);
    }

    private static OpenSearchSearchProperties searchDefaults() {
        return new OpenSearchSearchProperties(null, null, null, null, null, null, null, null, null, null);
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

    @Test
    void theTiebreakerOfPagingIsAFieldOfTheDocumentsNeverAMetadataField() {
        assertThat(new OpenSearchSearchProperties(null, null, null, null, null, null, null, null, null, null).pagingEnabled())
                .as("paging is off until a field is configured").isFalse();
        assertThat(new OpenSearchSearchProperties(null, null, null, null, null, null, null, null, null, "event.id").pagingEnabled()).isTrue();
        for (String bad : new String[] {"_id", "_doc", "event.*", "a b"}) {
            assertThatThrownBy(() -> new OpenSearchSearchProperties(null, null, null, null, null, null, null, null, null, bad))
                    .as(bad).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void theEmbeddedTomcatAndJacksonIncludeTheirSecurityFixes() {
        // The Boot parent stays on the starter's version, so the pom overrides these two; a parent bump must not lower them.
        int[] tomcat = Arrays.stream(ServerInfo.getServerNumber().split("\\.")).mapToInt(Integer::parseInt).toArray();
        assertThat(Arrays.compare(tomcat, new int[] {11, 0, 27})).as("Tomcat %s", ServerInfo.getServerNumber()).isNotNegative();
        assertThat(PackageVersion.VERSION).isGreaterThanOrEqualTo(new Version(3, 1, 7, null, null, null));
    }
}
