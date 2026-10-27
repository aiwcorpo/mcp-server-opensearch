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
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexOutsideScopeException;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexPattern;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.opensearch.core.domain.ResolvedIndices;
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
        public Optional<IndexDocument> findDocument(String index, String id) {
            return Optional.ofNullable(document);
        }
    };

    private final OpenSearchService service = new OpenSearchService(port, () -> SCOPE);

    private static LogQuery query(String base, boolean wildcard) {
        return new LogQuery(new IndexPattern(base, wildcard), "error", Instant.parse("2026-10-27T08:00:00Z"),
                Instant.parse("2026-10-27T09:00:00Z"), 10);
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
        hits = List.of(new LogHit("logs-team-a-1", "1", null, Map.of()), new LogHit("secret-1", "2", null, Map.of()));

        assertThat(service.searchLogs(query("logs-team-a-", true))).extracting(LogHit::index).containsExactly("logs-team-a-1");
    }

    @Test
    void aPatternOutsideTheScopeNeverReachesOpenSearch() {
        assertThatThrownBy(() -> service.searchLogs(query("secret", true))).isInstanceOf(IndexOutsideScopeException.class);
        assertThat(searched).isEmpty();
    }

    @Test
    void anAliasWithOneTargetOutsideTheScopeDeniesTheWholeSearch() {
        resolved = new ResolvedIndices(List.of(),
                List.of(new ResolvedIndices.Alias("logs-team-a-x", List.of("logs-team-a-1", "secret-1"))), List.of());

        assertThatThrownBy(() -> service.searchLogs(query("logs-team-a-x", false))).isInstanceOf(NotFoundOrDeniedException.class);
        assertThat(searched).isEmpty();
    }

    @Test
    void aDocumentFromAnotherIndexThanAskedIsNotReturned() {
        document = new IndexDocument("secret-1", "1", Map.of());

        assertThatThrownBy(() -> service.getDocument("logs-team-a-1", "1")).isInstanceOf(NotFoundOrDeniedException.class);
    }

    @Test
    void anAliasTargetThatIsHiddenDeniesTheCallEvenThoughResolveDoesNotSayIt() {
        resolved = new ResolvedIndices(List.of(),
                List.of(new ResolvedIndices.Alias("logs-team-a-x", List.of("logs-team-a-hidden"))), List.of());
        byName.put("logs-team-a-hidden", new ResolvedIndices(List.of(new ResolvedIndices.Index("logs-team-a-hidden", true)),
                List.of(), List.of()));

        assertThatThrownBy(() -> service.searchLogs(query("logs-team-a-x", false))).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.getDocument("logs-team-a-x", "1")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThat(searched).isEmpty();
        assertThat(resolvedExpressions).contains("logs-team-a-hidden");
    }

    @Test
    void anAliasTargetThatTheSecondResolveDoesNotReturnDeniesTheCall() {
        resolved = new ResolvedIndices(List.of(),
                List.of(new ResolvedIndices.Alias("logs-team-a-x", List.of("logs-team-a-1"))), List.of());
        byName.put("logs-team-a-1", new ResolvedIndices(List.of(), List.of(), List.of()));

        assertThatThrownBy(() -> service.searchLogs(query("logs-team-a-x", false))).isInstanceOf(NotFoundOrDeniedException.class);
    }

    @Test
    void anAliasWithAFilterOrRoutingIsNotReadByDocumentId() {
        resolved = new ResolvedIndices(List.of(),
                List.of(new ResolvedIndices.Alias("logs-team-a-x", List.of("logs-team-a-1"))), List.of());
        byName.put("logs-team-a-1", new ResolvedIndices(List.of(new ResolvedIndices.Index("logs-team-a-1", false)),
                List.of(), List.of()));
        document = new IndexDocument("logs-team-a-1", "1", Map.of());

        assertThat(service.getDocument("logs-team-a-x", "1").id()).isEqualTo("1");
        aliasRestricted = true;
        assertThatThrownBy(() -> service.getDocument("logs-team-a-x", "1")).isInstanceOf(NotFoundOrDeniedException.class);
    }
}
