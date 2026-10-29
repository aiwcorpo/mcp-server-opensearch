package pl.aiwcorpo.mcp.opensearch.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.QueryConstraint;
import pl.aiwcorpo.mcp.opensearch.core.domain.AggregateQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogBucket;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogQuery;
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpenSearchPort;
import pl.aiwcorpo.mcp.platform.resource.QueryScope;

class QueryScopeIndicesTest {

    private static final String ONLY_DATED = """
            {"indices":[{"name":"logs-team-a-2026.10","attributes":["open"]},{"name":"logs-team-a-2026.11","attributes":["open"]}],
             "aliases":[],"data_streams":[]}""";
    private static final String LOOK_ALIKES = """
            {"indices":[{"name":"logs-team-a-2026.10","attributes":["open"]},{"name":"logs-team-a","attributes":["open","hidden"]},
                        {"name":"logs-team-ab","attributes":["open"]},{"name":"logs-team-b-1","attributes":["open"]},
                        {"name":".ds-logs-team-a-stream-000001","attributes":["open","hidden"]},
                        {"name":"logs-team-a-%3Dodd","attributes":["open"]}],
             "aliases":[{"name":"logs-team-a-all","indices":["logs-team-b-1"]}],
             "data_streams":[{"name":"logs-team-a-stream","backing_indices":[".ds-logs-team-a-stream-000001"]}]}""";

    private final List<String> expressions = new ArrayList<>();
    private String response = ONLY_DATED;
    private List<String> granted = List.of("logs-team-a");

    private final OpenSearchPort port = new OpenSearchPort() {
        @Override
        public String resolveIndexResponse(String expression) {
            expressions.add(expression);
            return response;
        }

        @Override
        public List<LogHit> search(List<String> indices, IndexScope scope, LogQuery query) {
            throw new AssertionError();
        }

        @Override
        public List<LogBucket> aggregate(List<String> indices, IndexScope scope, AggregateQuery query) {
            throw new AssertionError();
        }

        @Override
        public Optional<IndexDocument> findDocument(String index, String id) {
            throw new AssertionError();
        }
    };

    private QueryScopeIndices indices() {
        return new QueryScopeIndices(() -> QueryScope.of(new QueryConstraint("OpenSearch::Index", Optional.empty(),
                "OpenSearch::Index", new TreeSet<>(granted))), port, new IndexScopeProperties(List.of("logs-team-a"), List.of()));
    }

    @Test
    void whenOnlyDatedIndicesExistTheNamesAreThoseIndices() {
        assertThat(indices().names()).containsExactly("logs-team-a-2026.10", "logs-team-a-2026.11");
        assertThat(expressions).containsExactly("logs-team-a,logs-team-a-*");
    }

    @Test
    void aliasesStreamsHiddenLookAlikesAndOddNamesAreLeftOut() {
        response = LOOK_ALIKES;

        assertThat(indices().names()).containsExactly("logs-team-a-2026.10");
    }

    @Test
    void anArgumentMustBeOneOfThoseNames() {
        response = LOOK_ALIKES;
        QueryScopeIndices indices = indices();

        assertThat(indices.argument("logs-team-a-2026.10")).hasValue("logs-team-a-2026.10");
        for (String refused : new String[] {"logs-team-a-all", "logs-team-a-stream", "logs-team-a", "logs-team-ab", "logs-team-b-1",
                "unknown-index"}) {
            assertThat(indices.argument(refused)).as(refused).isEmpty();
        }
    }

    @Test
    void anAnswerWithNothingInTheScopeIsNotQueriedAtAll() {
        response = QueryScope.EMPTY_RESOLVE_RESPONSE;

        assertThat(indices().names()).isEmpty();
    }

    @Test
    void anEmptyScopeDoesNotEvenAskOpenSearch() {
        granted = List.of();
        QueryScopeIndices indices = indices();

        assertThat(indices.names()).isEmpty();
        assertThat(indices.argument("logs-team-a-2026.10")).isEmpty();
        assertThat(expressions).isEmpty();
    }

    @Test
    void theAnswerIsNeverKeptBetweenCalls() {
        QueryScopeIndices indices = indices();
        indices.names();
        indices.names();
        indices.argument("logs-team-a-2026.10");

        assertThat(expressions).hasSize(3);
    }
}
