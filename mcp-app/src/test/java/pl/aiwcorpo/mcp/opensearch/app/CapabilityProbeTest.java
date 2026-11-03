package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.mock.env.MockEnvironment;
import pl.aiwcorpo.mcp.opensearch.core.domain.OpenSearchFeatures;
import pl.aiwcorpo.mcp.opensearch.integration.OpenSearchBackendProperties;
import pl.aiwcorpo.mcp.opensearch.integration.OpenSearchCapabilityProbe;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;

/** The probe against the {@code GET /} fixtures of OpenSearch 2.19, 3.x and Elasticsearch 7.10, over HTTP like at startup. */
class CapabilityProbeTest {

    private static StubBackend backend;

    @BeforeAll
    static void start() {
        backend = StubBackend.start();
    }

    @AfterAll
    static void stop() {
        backend.stop();
    }

    private static Capabilities probe(String fixtures, boolean assumeTested) {
        backend.useFixtures(fixtures);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        return new OpenSearchCapabilityProbe(
                new OpenSearchBackendProperties(backend.baseUrl(), null, null, null, null, false, assumeTested),
                new DefaultListableBeanFactory().getBeanProvider(SslBundles.class), environment).probe();
    }

    @Test
    void openSearch219IsSupportedWithTheFullSet() {
        Capabilities found = probe(StubBackend.FIXTURES_2_19, false);

        assertThat(found.state()).isEqualTo(Capabilities.State.SUPPORTED);
        assertThat(found.backendVersion()).isEqualTo("2.19.4");
        assertThat(found.features()).isEqualTo(OpenSearchFeatures.TESTED);
    }

    @Test
    void openSearch3IsSupportedWithTheFullSet() {
        Capabilities found = probe(StubBackend.FIXTURES_3, false);

        assertThat(found.state()).isEqualTo(Capabilities.State.SUPPORTED);
        assertThat(found.backendVersion()).isEqualTo("3.9.0");
        assertThat(found.features()).isEqualTo(OpenSearchFeatures.TESTED);
    }

    @Test
    void elasticsearchWithoutADistributionIsDegradedToTheBaseline() {
        Capabilities found = probe(StubBackend.FIXTURES_ES_7_10, false);

        assertThat(found.state()).isEqualTo(Capabilities.State.DEGRADED);
        assertThat(found.backendVersion()).isEqualTo("7.10.2");
        assertThat(found.features()).isEqualTo(OpenSearchFeatures.BASELINE);
        assertThat(found.detail()).contains("not OpenSearch");
    }

    @Test
    void aRefusedAnonymousRootIsDegradedWithoutAVersion() {
        backend.anonymousRoot(false);
        Capabilities found = probe(StubBackend.FIXTURES_2_19, false);
        backend.anonymousRoot(true);

        assertThat(found.state()).isEqualTo(Capabilities.State.DEGRADED);
        assertThat(found.backendVersion()).isNull();
        assertThat(found.features()).isEqualTo(OpenSearchFeatures.BASELINE);
        assertThat(backend.probeAuthorizations()).contains("null");
    }

    @Test
    void theOperatorCanStateThatARefusingClusterIsATestedVersion() {
        backend.anonymousRoot(false);
        Capabilities assumed = probe(StubBackend.FIXTURES_2_19, true);
        Capabilities notAssumed = probe(StubBackend.FIXTURES_2_19, false);
        backend.anonymousRoot(true);

        assertThat(assumed.state()).isEqualTo(Capabilities.State.SUPPORTED);
        assertThat(assumed.features()).isEqualTo(OpenSearchFeatures.TESTED);
        assertThat(notAssumed.state()).isEqualTo(Capabilities.State.DEGRADED);
    }

    @Test
    void theAssumptionDoesNotOverrideAnAnswerThatNamesAnotherProduct() {
        assertThat(probe(StubBackend.FIXTURES_ES_7_10, true).state()).isEqualTo(Capabilities.State.DEGRADED);
    }
}
