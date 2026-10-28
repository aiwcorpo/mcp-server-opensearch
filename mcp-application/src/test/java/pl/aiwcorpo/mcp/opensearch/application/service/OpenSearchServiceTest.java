package pl.aiwcorpo.mcp.opensearch.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.opensearch.core.domain.AggregateQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.AggregationOverAliasException;
import pl.aiwcorpo.mcp.opensearch.core.domain.GroupBy;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexOutsideScopeException;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexPattern;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogBucket;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogFilter;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.opensearch.core.domain.ResolvedIndex;
import pl.aiwcorpo.mcp.opensearch.core.domain.ResolvedIndices;
import pl.aiwcorpo.mcp.opensearch.core.domain.TooManyIndicesException;
import pl.aiwcorpo.mcp.opensearch.core.domain.UnsupportedIndexTargetException;
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpenSearchPort;

class OpenSearchServiceTest {

    private static final IndexScope SCOPE = new IndexScope(List.of("logs-team-a"));

    private final List<String> searched = new ArrayList<>();
    private ResolvedIndices resolved = new ResolvedIndices(List.of(new ResolvedIndices.Index("logs-team-a-1", false)),
            List.of(), List.of());
    private final Map<String, ResolvedIndices> byName = new HashMap<>();
    private final List<String> resolvedExpressions = new ArrayList<>();
    private boolean aliasRestricted;
    private List<LogHit> hits = List.of();
    private IndexDocument document;
    private final List<List<String>> aggregated = new ArrayList<>();
    private IndexScope scope = SCOPE;

    private final OpenSearchPort port = new OpenSearchPort() {
        @Override
        public Optional<ResolvedIndices> resolve(String expression) {
            resolvedExpressions.add(expression);
            return Optional.ofNullable(byName.getOrDefault(expression, resolved));
        }

        @Override
        public boolean aliasHasFilterOrRouting(String alias) {
            return aliasRestricted;
        }

        @Override
        public List<LogHit> search(String expression, IndexScope scope, LogQuery query) {
            searched.add(expression);
            return hits;
        }

        @Override
        public List<LogBucket> aggregate(List<String> indices, IndexScope scope, AggregateQuery query) {
            aggregated.add(indices);
            return List.of();
        }

        @Override
        public Optional<IndexDocument> findDocument(String index, String id) {
            return Optional.ofNullable(document);
        }
    };

    private final OpenSearchService service = new OpenSearchService(port, () -> scope);

    private static LogQuery query(String base, boolean wildcard) {
        return new LogQuery(new IndexPattern(base, wildcard), new LogFilter("error", null, null), Instant.parse("2026-10-27T08:00:00Z"),
                Instant.parse("2026-10-27T09:00:00Z"), 10);
    }

    private static AggregateQuery aggregation(String base, boolean wildcard) {
        return new AggregateQuery(new IndexPattern(base, wildcard), new LogFilter(null, null, null),
                Instant.parse("2026-10-27T08:00:00Z"), Instant.parse("2026-10-27T09:00:00Z"), GroupBy.LEVEL, 10);
    }

    private static ResolvedIndices indices(String... names) {
        return new ResolvedIndices(java.util.Arrays.stream(names).map(n -> new ResolvedIndices.Index(n, false)).toList(),
                List.of(), List.of());
    }

    @Test
    void theScopeStopsAtTheDash() {
        assertThat(SCOPE.covers("logs-team-a")).isTrue();
        assertThat(SCOPE.covers("logs-team-a-2026.10.27")).isTrue();
        assertThat(SCOPE.covers("logs-team-ab")).isFalse();
        assertThat(SCOPE.covers("logs-team")).isFalse();
        assertThat(SCOPE.covers(".logs-team-a")).isFalse();
        assertThat(SCOPE.expressionFor(new IndexPattern("logs-team-a", true))).hasValue("logs-team-a,logs-team-a-*");
        assertThat(SCOPE.expressionFor(new IndexPattern("logs-team-a-20", true))).hasValue("logs-team-a-20*");
        assertThat(SCOPE.expressionFor(new IndexPattern("logs-team-ab", true))).isEmpty();
    }

    @Test
    void hitsOutsideTheScopeAreDroppedEvenIfOpenSearchReturnsThem() {
        hits = List.of(new LogHit("logs-team-a-1", "1", null, List.of(), false), new LogHit("secret-1", "2", null, List.of(), false));

        assertThat(service.searchLogs(query("logs-team-a-", true), index -> true)).extracting(LogHit::index).containsExactly("logs-team-a-1");
    }

    @Test
    void aPatternOutsideTheScopeNeverReachesOpenSearch() {
        assertThatThrownBy(() -> service.searchLogs(query("secret", true), index -> true)).isInstanceOf(IndexOutsideScopeException.class);
        assertThat(searched).isEmpty();
    }

    @Test
    void anAliasWithOneTargetOutsideTheScopeDeniesTheWholeSearch() {
        resolved = new ResolvedIndices(List.of(),
                List.of(new ResolvedIndices.Alias("logs-team-a-x", List.of("logs-team-a-1", "secret-1"))), List.of());

        assertThatThrownBy(() -> service.searchLogs(query("logs-team-a-x", false), index -> true)).isInstanceOf(NotFoundOrDeniedException.class);
        assertThat(searched).isEmpty();
    }

    @Test
    void aDocumentFromAnotherIndexThanAskedIsNotReturned() {
        document = new IndexDocument("secret-1", "1", List.of(), false);

        assertThatThrownBy(() -> service.getDocument("logs-team-a-1", "1")).isInstanceOf(NotFoundOrDeniedException.class);
    }

    @Test
    void anAliasTargetThatIsHiddenDeniesTheCallEvenThoughResolveDoesNotSayIt() {
        resolved = new ResolvedIndices(List.of(),
                List.of(new ResolvedIndices.Alias("logs-team-a-x", List.of("logs-team-a-hidden"))), List.of());
        byName.put("logs-team-a-hidden", new ResolvedIndices(List.of(new ResolvedIndices.Index("logs-team-a-hidden", true)),
                List.of(), List.of()));

        assertThatThrownBy(() -> service.searchLogs(query("logs-team-a-x", false), index -> true)).isInstanceOf(NotFoundOrDeniedException.class);
        assertThat(service.resolveIndex("logs-team-a-x")).isEmpty();
        assertThat(searched).isEmpty();
        assertThat(resolvedExpressions).contains("logs-team-a-hidden");
    }

    @Test
    void anAliasTargetThatTheSecondResolveDoesNotReturnDeniesTheCall() {
        resolved = new ResolvedIndices(List.of(),
                List.of(new ResolvedIndices.Alias("logs-team-a-x", List.of("logs-team-a-1"))), List.of());
        byName.put("logs-team-a-1", new ResolvedIndices(List.of(), List.of(), List.of()));

        assertThatThrownBy(() -> service.searchLogs(query("logs-team-a-x", false), index -> true)).isInstanceOf(NotFoundOrDeniedException.class);
    }

    @Test
    void anEmptyScopeRefusesEverythingBeforeAnyRequestToOpenSearch() {
        scope = new IndexScope(List.of());

        assertThatThrownBy(() -> service.searchLogs(query("logs-team-a", true), index -> true)).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.aggregateLogs(aggregation("logs-team-a", true), index -> true))
                .isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.getDocument("logs-team-a-1", "1")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThat(service.resolveIndex("logs-team-a-1")).isEmpty();
        assertThat(resolvedExpressions).isEmpty();
        assertThat(searched).isEmpty();
    }

    @Test
    void anExactNameWithNothingReadableLooksMissingAndAWildcardLooksEmpty() {
        resolved = indices("logs-team-a-hr-1");

        assertThatThrownBy(() -> service.searchLogs(query("logs-team-a-hr-1", false), index -> false))
                .isInstanceOf(NotFoundOrDeniedException.class);
        assertThat(service.searchLogs(query("logs-team-a-hr-", true), index -> false)).isEmpty();
        assertThat(searched).isEmpty();
    }

    @Test
    void aDataStreamNamedExactlyLooksMissing() {
        resolved = new ResolvedIndices(List.of(), List.of(), List.of("logs-team-a-s"));

        assertThatThrownBy(() -> service.searchLogs(query("logs-team-a-s", false), index -> true))
                .isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.searchLogs(query("logs-team-a-s", true), index -> true))
                .isInstanceOf(UnsupportedIndexTargetException.class);
    }

    @Test
    void aggregatesRefuseAnAliasButSearchesGoThroughIt() {
        resolved = new ResolvedIndices(List.of(new ResolvedIndices.Index("logs-team-a-1", false)),
                List.of(new ResolvedIndices.Alias("logs-team-a-x", List.of("logs-team-a-1"))), List.of());
        byName.put("logs-team-a-1", indices("logs-team-a-1"));

        assertThatThrownBy(() -> service.aggregateLogs(aggregation("logs-team-a-", true), index -> true))
                .isInstanceOf(AggregationOverAliasException.class);
        assertThat(aggregated).isEmpty();
        service.searchLogs(query("logs-team-a-", true), index -> true);
        assertThat(searched).hasSize(1);
    }

    @Test
    void anAliasLeadingOutsideTheScopeIsNotFoundAndNotNamedAsAnAliasByAnAggregation() {
        resolved = new ResolvedIndices(List.of(),
                List.of(new ResolvedIndices.Alias("logs-team-a-x", List.of("secret-1"))), List.of());

        assertThatThrownBy(() -> service.aggregateLogs(aggregation("logs-team-a-x", false), index -> true))
                .isInstanceOf(NotFoundOrDeniedException.class);
    }

    @Test
    void anAggregationCountsOnlyTheIndicesThePolicyAllows() {
        resolved = indices("logs-team-a-1", "logs-team-a-hr-1", "logs-team-a-2");

        service.aggregateLogs(aggregation("logs-team-a-", true), index -> !index.startsWith("logs-team-a-hr"));

        assertThat(aggregated).containsExactly(List.of("logs-team-a-1", "logs-team-a-2"));
    }

    @Test
    void anAggregationWithNothingReadableIsEmptyForAWildcardAndNotFoundForAName() {
        resolved = indices("logs-team-a-hr-1");

        assertThat(service.aggregateLogs(aggregation("logs-team-a-", true), index -> false)).isEmpty();
        assertThatThrownBy(() -> service.aggregateLogs(aggregation("logs-team-a-hr-1", false), index -> false))
                .isInstanceOf(NotFoundOrDeniedException.class);
        assertThat(aggregated).isEmpty();
    }

    @Test
    void anAggregationOverTooManyIndicesIsRefused() {
        resolved = indices(java.util.stream.IntStream.rangeClosed(1, 101).mapToObj(i -> "logs-team-a-" + i).toArray(String[]::new));

        assertThatThrownBy(() -> service.aggregateLogs(aggregation("logs-team-a-", true), index -> true))
                .isInstanceOf(TooManyIndicesException.class);
    }

    @Test
    void anAliasOfOneIndexResolvesToTheIndexBehindItAndOtherTargetsToNothing() {
        resolved = new ResolvedIndices(List.of(),
                List.of(new ResolvedIndices.Alias("logs-team-a-x", List.of("logs-team-a-1"))), List.of());
        byName.put("logs-team-a-1", indices("logs-team-a-1"));
        assertThat(service.resolveIndex("logs-team-a-x")).hasValue(new ResolvedIndex("logs-team-a-1"));

        resolved = new ResolvedIndices(List.of(),
                List.of(new ResolvedIndices.Alias("logs-team-a-x", List.of("logs-team-a-1", "logs-team-a-2"))), List.of());
        assertThat(service.resolveIndex("logs-team-a-x")).as("an alias of several indices").isEmpty();

        resolved = new ResolvedIndices(List.of(), List.of(), List.of("logs-team-a-x"));
        assertThat(service.resolveIndex("logs-team-a-x")).as("a data stream").isEmpty();

        assertThat(service.resolveIndex("secret-1")).as("outside the scope").isEmpty();
        assertThat(service.resolveIndex("logs-team-ab-1")).as("the neighbour of a prefix").isEmpty();
    }

    @Test
    void anAliasWithAFilterOrRoutingIsNotReadByDocumentId() {
        resolved = new ResolvedIndices(List.of(),
                List.of(new ResolvedIndices.Alias("logs-team-a-x", List.of("logs-team-a-1"))), List.of());
        byName.put("logs-team-a-1", new ResolvedIndices(List.of(new ResolvedIndices.Index("logs-team-a-1", false)),
                List.of(), List.of()));
        document = new IndexDocument("logs-team-a-1", "1", List.of(), false);

        assertThat(service.resolveIndex("logs-team-a-x")).isPresent();
        aliasRestricted = true;
        assertThat(service.resolveIndex("logs-team-a-x")).isEmpty();
    }
}
