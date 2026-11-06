package pl.aiwcorpo.mcp.opensearch.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.opensearch.core.domain.ResolvedIndex;

class OpenSearchIndexResolverTest {

    private final OpenSearchIndexResolver resolver = new OpenSearchIndexResolver(null,
            index -> index.startsWith("logs-team-a-hr") ? List.of("restricted") : List.of());

    @Test
    void anIndexCarriesTheScopeOfEveryDashPrefixOfItsName() {
        ResolvedResource resource = resolver.describe(new ResolvedIndex("logs-team-a-2026.10"));

        assertThat(resource.id()).isEqualTo("logs-team-a-2026.10");
        assertThat(resource.scopes()).containsExactlyInAnyOrder("OpenSearch::Index/logs", "OpenSearch::Index/logs-team",
                "OpenSearch::Index/logs-team-a", "OpenSearch::Index/logs-team-a-2026.10");
    }

    @Test
    void aPrefixThatCannotBeGrantedIsLeftOut() {
        assertThat(resolver.describe(new ResolvedIndex("logs.-x")).scopes()).containsExactly("OpenSearch::Index/logs.-x");
        assertThat(resolver.describe(new ResolvedIndex("a--b")).scopes()).containsExactlyInAnyOrder("OpenSearch::Index/a",
                "OpenSearch::Index/a--b");
    }

    @Test
    void theTagsComeFromTheClassifierNotFromTheName() {
        assertThat(resolver.describe(new ResolvedIndex("logs-team-a-hr-1")).attributes()).containsEntry("tags", List.of("restricted"));
        assertThat(resolver.describe(new ResolvedIndex("logs-team-a-1")).attributes()).containsEntry("tags", List.of());
    }

    @Test
    void theProbeIsTheIndexOfItsNameAloneWithNoTagsAndAsksNothing() {
        ResolvedResource probe = resolver.probe("logs-team-a-hr-1").orElseThrow();

        assertThat(probe.id()).isEqualTo("logs-team-a-hr-1");
        assertThat(probe.attributes()).containsEntry("tags", List.of());
        assertThat(probe.scopes()).contains("OpenSearch::Index/logs-team-a", "OpenSearch::Index/logs-team-a-hr-1");
    }
}
