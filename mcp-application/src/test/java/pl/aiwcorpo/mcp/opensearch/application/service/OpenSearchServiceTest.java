package pl.aiwcorpo.mcp.opensearch.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.opensearch.core.domain.AggregateQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.GroupBy;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogBucket;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogFilter;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.opensearch.core.domain.TooManyIndicesException;
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpenSearchPort;
import pl.aiwcorpo.mcp.opensearch.core.port.out.ScopedIndices;

class OpenSearchServiceTest {

    private static final IndexScope SCOPE = new IndexScope(List.of("logs-team-a"));
    private static final Instant FROM = Instant.parse("2026-10-27T08:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-27T09:00:00Z");

    private List<String> names = List.of("logs-team-a", "logs-team-a-1", "logs-team-a-hr-1", "logs-team-ab-1");
    private IndexScope scope = SCOPE;
    private List<LogHit> hits = List.of();
    private IndexDocument document;
    private final List<List<String>> searched = new ArrayList<>();
    private final List<List<String>> aggregated = new ArrayList<>();
    private int resolutions;

    private final ScopedIndices indices = new ScopedIndices() {
        @Override
        public List<String> names() {
            resolutions++;
            return names;
        }

        @Override
        public Optional<String> argument(String index) {
            return names.contains(index) ? Optional.of(index) : Optional.empty();
        }
    };

    private final OpenSearchPort port = new OpenSearchPort() {
        @Override
        public String resolveIndexResponse(String expression) {
            throw new AssertionError("the use case works on ScopedIndices, not on raw resolve answers");
        }

        @Override
        public List<LogHit> search(List<String> targets, IndexScope scope, LogQuery query) {
            searched.add(targets);
            return hits;
        }

        @Override
        public List<LogBucket> aggregate(List<String> targets, IndexScope scope, AggregateQuery query) {
            aggregated.add(targets);
            return List.of();
        }

        @Override
        public Optional<IndexDocument> findDocument(String index, String id) {
            return Optional.ofNullable(document);
        }
    };

    private final OpenSearchService service = new OpenSearchService(port, () -> scope, indices);

    private static LogQuery query(String prefix) {
        return new LogQuery(prefix, new LogFilter("error", null, null), FROM, TO, 10);
    }

    private static AggregateQuery aggregate(String prefix) {
        return new AggregateQuery(prefix, new LogFilter(null, null, null), FROM, TO, GroupBy.LEVEL, 10);
    }

    private static LogHit hit(String index) {
        return new LogHit(index, "1", null, List.of(), false);
    }

    @Test
    void aPrefixPicksTheNamedIndexAndItsDashChildrenNeverANeighbour() {
        service.searchLogs(query("logs-team-a"), index -> true);

        assertThat(searched).containsExactly(List.of("logs-team-a", "logs-team-a-1", "logs-team-a-hr-1"));
    }

    @Test
    void aLongerPrefixNarrowsAndAShorterOneStaysInsideTheScopeNames() {
        service.searchLogs(query("logs-team-a-1"), index -> true);
        service.searchLogs(query("logs"), index -> true);

        assertThat(searched).containsExactly(List.of("logs-team-a-1"),
                List.of("logs-team-a", "logs-team-a-1", "logs-team-a-hr-1", "logs-team-ab-1"));
    }

    @Test
    void whatThePolicyRefusesIsNotQueriedNorCounted() {
        service.searchLogs(query("logs-team-a"), index -> !index.contains("-hr-"));
        service.aggregateLogs(aggregate("logs-team-a"), index -> !index.contains("-hr-"));

        assertThat(searched).containsExactly(List.of("logs-team-a", "logs-team-a-1"));
        assertThat(aggregated).containsExactly(List.of("logs-team-a", "logs-team-a-1"));
    }

    @Test
    void nothingToQueryIsAnEmptyResultWithoutACallToOpenSearch() {
        names = List.of();

        assertThat(service.searchLogs(query("logs-team-a"), index -> true)).isEmpty();
        assertThat(service.aggregateLogs(aggregate("logs-team-a"), index -> true)).isEmpty();
        assertThat(searched).isEmpty();
        assertThat(aggregated).isEmpty();
    }

    @Test
    void aPrefixWithNoReadableIndexIsAnEmptyResultLikeAnUnknownOne() {
        assertThat(service.searchLogs(query("logs-team-a-hr"), index -> !index.contains("-hr-"))).isEmpty();
        assertThat(service.searchLogs(query("nothing"), index -> true)).isEmpty();
        assertThat(service.aggregateLogs(aggregate("nothing"), index -> true)).isEmpty();
        assertThat(searched).isEmpty();
        assertThat(aggregated).isEmpty();
    }

    @Test
    void hitsOutsideTheScopeAreDropped() {
        hits = List.of(hit("logs-team-a-1"), hit("logs-team-ab-1"), hit("secret-hr-1"), hit(".security"));

        assertThat(service.searchLogs(query("logs-team-a"), index -> true)).extracting(LogHit::index)
                .containsExactly("logs-team-a-1");
    }

    @Test
    void moreIndicesThanOneRequestMayNameIsRefused() {
        names = IntStream.rangeClosed(1, OpenSearchService.MAX_INDICES + 1).mapToObj(i -> "logs-team-a-" + i).toList();

        assertThatThrownBy(() -> service.searchLogs(query("logs-team-a"), index -> true)).isInstanceOf(TooManyIndicesException.class);
        assertThatThrownBy(() -> service.aggregateLogs(aggregate("logs-team-a"), index -> true)).isInstanceOf(TooManyIndicesException.class);
        assertThat(searched).isEmpty();
        assertThat(aggregated).isEmpty();
    }

    @Test
    void theHitsOfOneSearchAreJudgedFromOneResolution() {
        assertThat(service.resolveIndices(List.of("logs-team-a-1", "logs-team-a-1", "secret-hr-1", "logs-team-a-all")))
                .containsExactly("logs-team-a-1");
        assertThat(resolutions).isEqualTo(1);
    }

    @Test
    void anIndexIsResolvedOnlyWhenItIsAConcreteIndexOfTheScope() {
        assertThat(service.resolveIndex("logs-team-a-1")).isPresent();
        assertThat(service.resolveIndex("logs-team-a-all")).as("an alias is not among the names").isEmpty();
        assertThat(service.resolveIndex("secret-hr-1")).isEmpty();
    }

    @Test
    void aDocumentIsReadOnlyFromAnIndexInsideTheScope() {
        document = new IndexDocument("logs-team-a-1", "1", List.of(), false);

        assertThat(service.getDocument("logs-team-a-1", "1").index()).isEqualTo("logs-team-a-1");
        assertThatThrownBy(() -> service.getDocument("secret-hr-1", "1")).isInstanceOf(NotFoundOrDeniedException.class);
        document = new IndexDocument("secret-hr-1", "1", List.of(), false);
        assertThatThrownBy(() -> service.getDocument("logs-team-a-1", "1")).isInstanceOf(NotFoundOrDeniedException.class);
        document = null;
        assertThatThrownBy(() -> service.getDocument("logs-team-a-1", "1")).isInstanceOf(NotFoundOrDeniedException.class);
        scope = new IndexScope(List.of());
        assertThatThrownBy(() -> service.getDocument("logs-team-a-1", "1")).isInstanceOf(NotFoundOrDeniedException.class);
    }
}
