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
import pl.aiwcorpo.mcp.opensearch.core.domain.ClusterHealth;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexInfo;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexMapping;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexPage;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogCount;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogPage;
import pl.aiwcorpo.mcp.opensearch.core.domain.QueryCheck;
import pl.aiwcorpo.mcp.opensearch.core.domain.SearchAfter;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;
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
    private SearchAfter next;
    private IndexMapping mapping;
    private final List<List<String>> searched = new ArrayList<>();
    private final List<List<String>> aggregated = new ArrayList<>();
    private final List<List<String>> counted = new ArrayList<>();
    private final List<List<String>> described = new ArrayList<>();
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
        public LogPage search(List<String> targets, IndexScope scope, LogQuery query) {
            searched.add(targets);
            return new LogPage(hits, next);
        }

        @Override
        public LogCount count(List<String> targets, IndexScope scope, LogQuery query) {
            counted.add(targets);
            return new LogCount(42, true);
        }

        @Override
        public QueryCheck validate(List<String> targets, IndexScope scope, LogQuery query) {
            counted.add(targets);
            return new QueryCheck(true, targets.size(), List.of(), UntrustedText.plain("+message:x", 10));
        }

        @Override
        public List<IndexInfo> indexInfo(List<String> targets) {
            described.add(targets);
            return targets.stream().map(name -> new IndexInfo(name, "green", 1L, 2L)).toList();
        }

        @Override
        public Optional<IndexMapping> findMapping(String index) {
            return Optional.ofNullable(mapping);
        }

        @Override
        public ClusterHealth clusterHealth() {
            return new ClusterHealth("green", false, 3, 10, 0, 0, 0, 100);
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

        assertThat(service.searchLogs(query("logs-team-a"), index -> true).hits()).isEmpty();
        assertThat(service.aggregateLogs(aggregate("logs-team-a"), index -> true)).isEmpty();
        assertThat(searched).isEmpty();
        assertThat(aggregated).isEmpty();
    }

    @Test
    void aPrefixWithNoReadableIndexIsAnEmptyResultLikeAnUnknownOne() {
        assertThat(service.searchLogs(query("logs-team-a-hr"), index -> !index.contains("-hr-")).hits()).isEmpty();
        assertThat(service.searchLogs(query("nothing"), index -> true).hits()).isEmpty();
        assertThat(service.aggregateLogs(aggregate("nothing"), index -> true)).isEmpty();
        assertThat(searched).isEmpty();
        assertThat(aggregated).isEmpty();
    }

    @Test
    void hitsOutsideTheScopeAreDropped() {
        hits = List.of(hit("logs-team-a-1"), hit("logs-team-ab-1"), hit("secret-hr-1"), hit(".security"));

        assertThat(service.searchLogs(query("logs-team-a"), index -> true).hits()).extracting(LogHit::index)
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
    void onlyIndicesOfTheTimeRangeAndUndatedOnesAreQueried() {
        names = List.of("logs-team-a", "logs-team-a-2026.10.26", "logs-team-a-2026.10.27", "logs-team-a-2026.09", "logs-team-a-2026.10",
                "logs-team-a-app");

        service.searchLogs(query("logs-team-a"), index -> true);
        service.aggregateLogs(aggregate("logs-team-a"), index -> true);

        List<String> expected = List.of("logs-team-a", "logs-team-a-2026.10.26", "logs-team-a-2026.10.27", "logs-team-a-2026.10", "logs-team-a-app");
        assertThat(searched).containsExactly(expected);
        assertThat(aggregated).containsExactly(expected);
    }

    @Test
    void theDateNeverAddsAnIndexTheScopeDidNotList() {
        names = List.of("logs-team-a-2026.10.27");

        service.searchLogs(query("logs-team-a"), index -> true);

        assertThat(searched).containsExactly(List.of("logs-team-a-2026.10.27"));
    }

    @Test
    void theLimitCountsOnlyWhatMeetsTheRange() {
        List<String> outOfRange = IntStream.rangeClosed(1, 200).mapToObj(i -> "logs-team-a-2025.%02d.%02d".formatted((i % 12) + 1, (i % 28) + 1)).distinct().toList();
        names = new ArrayList<>(outOfRange);
        names.add("logs-team-a-2026.10.27");

        service.searchLogs(query("logs-team-a"), index -> true);
        assertThat(searched).containsExactly(List.of("logs-team-a-2026.10.27"));

        searched.clear();
        List<String> undated = IntStream.rangeClosed(1, OpenSearchService.MAX_INDICES + 1).mapToObj(i -> "logs-team-a-app" + i).toList();
        names = undated;
        assertThatThrownBy(() -> service.searchLogs(query("logs-team-a"), index -> true)).isInstanceOf(TooManyIndicesException.class);
    }

    @Test
    void theLengthOfThePathIsLimitedNotOnlyTheCount() {
        names = IntStream.rangeClosed(1, 40).mapToObj(i -> "logs-team-a-" + "x".repeat(100) + i).toList();

        assertThatThrownBy(() -> service.searchLogs(query("logs-team-a"), index -> true)).isInstanceOf(TooManyIndicesException.class);
        assertThatThrownBy(() -> service.aggregateLogs(aggregate("logs-team-a"), index -> true)).isInstanceOf(TooManyIndicesException.class);
        names = IntStream.rangeClosed(1, 20).mapToObj(i -> "logs-team-a-" + "x".repeat(100) + i).toList();
        service.searchLogs(query("logs-team-a"), index -> true);
        assertThat(searched).hasSize(1);
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

    @Test
    void thePositionAfterTheLastRawHitSurvivesTheDroppingOfHitsOutsideTheScope() {
        hits = List.of(hit("secret-hr-1"));
        next = new SearchAfter(1761556502123L, "doc-9");

        LogPage page = service.searchLogs(query("logs-team-a"), index -> true);

        assertThat(page.hits()).isEmpty();
        assertThat(page.next()).isEqualTo(next);
    }

    @Test
    void countAndValidateQueryOnlyTheIndicesTheCallerMayRead() {
        assertThat(service.countLogs(query("logs-team-a"), index -> !index.contains("-hr-")).count()).isEqualTo(42);
        service.validateQuery(query("logs-team-a"), index -> !index.contains("-hr-"));

        assertThat(counted).containsExactly(List.of("logs-team-a", "logs-team-a-1"), List.of("logs-team-a", "logs-team-a-1"));
    }

    @Test
    void countAndValidateWithNothingToQueryAskNoOne() {
        names = List.of();

        assertThat(service.countLogs(query("logs-team-a"), index -> true)).isEqualTo(new LogCount(0, true));
        assertThat(service.validateQuery(query("logs-team-a"), index -> true).checked()).isZero();
        assertThat(counted).isEmpty();
    }

    @Test
    void indicesAreListedByNameInPagesWithTheLastNameAsTheContinuation() {
        names = List.of("logs-team-a", "logs-team-a-1", "logs-team-a-2", "logs-team-a-3", "logs-team-ab-1");

        IndexPage first = service.listIndices("logs-team-a", null, 2, index -> true);
        IndexPage second = service.listIndices("logs-team-a", first.lastName(), 2, index -> true);

        assertThat(first.indices()).extracting(IndexInfo::name).containsExactly("logs-team-a", "logs-team-a-1");
        assertThat(first.lastName()).isEqualTo("logs-team-a-1");
        assertThat(second.indices()).extracting(IndexInfo::name).containsExactly("logs-team-a-2", "logs-team-a-3");
        assertThat(second.lastName()).as("the last page has no continuation").isNull();
        assertThat(described).containsExactly(List.of("logs-team-a", "logs-team-a-1"), List.of("logs-team-a-2", "logs-team-a-3"));
    }

    @Test
    void theIndexListSkipsWhatThePolicyRefusesBeforeCountingThePage() {
        service.listIndices("logs-team-a", null, 2, index -> !index.contains("-hr-"));

        assertThat(described).containsExactly(List.of("logs-team-a", "logs-team-a-1"));
        assertThat(service.listIndices("nothing", null, 2, index -> true).indices()).isEmpty();
        assertThat(described).hasSize(1);
    }

    @Test
    void aMappingIsReadOnlyFromAnIndexInsideTheScope() {
        mapping = new IndexMapping("logs-team-a-1", List.of(), false);

        assertThat(service.getIndexMapping("logs-team-a-1").index()).isEqualTo("logs-team-a-1");
        assertThatThrownBy(() -> service.getIndexMapping("secret-hr-1")).isInstanceOf(NotFoundOrDeniedException.class);
        mapping = null;
        assertThatThrownBy(() -> service.getIndexMapping("logs-team-a-1")).isInstanceOf(NotFoundOrDeniedException.class);
    }
}
