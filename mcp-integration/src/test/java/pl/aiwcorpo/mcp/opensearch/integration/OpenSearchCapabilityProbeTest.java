package pl.aiwcorpo.mcp.opensearch.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.opensearch.core.domain.OpenSearchFeatures;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;
import tools.jackson.databind.json.JsonMapper;

/** How the answer of {@code GET /} becomes a state: the edges of the tested range and answers that are not versions. */
class OpenSearchCapabilityProbeTest {

    private static Capabilities classify(String distribution, String number) {
        return OpenSearchCapabilityProbe.classify(new OpenSearchCapabilityProbe.RootResponse(
                new OpenSearchCapabilityProbe.Version(number, distribution)));
    }

    @Test
    void theTestedRangeIsOpenSearch2xAnd3x() {
        for (String version : new String[] {"2.0.0", "2.11.1", "2.19.4", "3.0.0", "3.9.0", "3.12.4-SNAPSHOT"}) {
            assertThat(classify("opensearch", version).state()).as(version).isEqualTo(Capabilities.State.SUPPORTED);
        }
    }

    @Test
    void versionsOutsideTheRangeAreDegradedToTheBaseline() {
        for (String version : new String[] {"1.3.20", "4.0.0", "10.1.0"}) {
            Capabilities found = classify("opensearch", version);

            assertThat(found.state()).as(version).isEqualTo(Capabilities.State.DEGRADED);
            assertThat(found.features()).as(version).isEqualTo(OpenSearchFeatures.BASELINE);
            assertThat(found.backendVersion()).isNotNull();
        }
    }

    @Test
    void anotherDistributionOrNoneIsDegradedWhateverItsVersion() {
        assertThat(classify(null, "7.10.2").state()).isEqualTo(Capabilities.State.DEGRADED);
        assertThat(classify("elasticsearch", "2.19.4").state()).isEqualTo(Capabilities.State.DEGRADED);
        assertThat(classify("OpenSearch", "2.19.4").state()).as("the name is compared exactly").isEqualTo(Capabilities.State.DEGRADED);
    }

    @Test
    void anAnswerWithoutAVersionIsUnavailableNeverGuessed() throws Exception {
        JsonMapper json = JsonMapper.builder().build();

        for (String body : new String[] {"{}", "{\"version\":{}}", "{\"version\":{\"distribution\":\"opensearch\"}}",
                "{\"version\":{\"distribution\":\"opensearch\",\"number\":\"<html>login</html>\"}}",
                "{\"version\":{\"distribution\":\"opensearch\",\"number\":\"2\"}}"}) {
            Capabilities found = OpenSearchCapabilityProbe.classify(
                    json.readValue(body, OpenSearchCapabilityProbe.RootResponse.class));

            assertThat(found.state()).as(body).isEqualTo(Capabilities.State.UNAVAILABLE);
            assertThat(found.features()).as(body).isEmpty();
        }
        assertThat(OpenSearchCapabilityProbe.classify(null).state()).isEqualTo(Capabilities.State.UNAVAILABLE);
    }

    @Test
    void unknownFieldsOfTheAnswerAreIgnored() throws Exception {
        OpenSearchCapabilityProbe.RootResponse root = JsonMapper.builder().build().readValue(
                "{\"name\":\"n\",\"cluster_name\":\"c\",\"version\":{\"distribution\":\"opensearch\",\"number\":\"3.9.0\","
                        + "\"build_type\":\"tar\",\"lucene_version\":\"10.2.2\"},\"tagline\":\"t\"}",
                OpenSearchCapabilityProbe.RootResponse.class);

        assertThat(OpenSearchCapabilityProbe.classify(root).backendVersion()).isEqualTo("3.9.0");
    }
}
