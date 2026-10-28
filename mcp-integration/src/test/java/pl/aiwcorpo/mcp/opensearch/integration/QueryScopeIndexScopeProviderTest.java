package pl.aiwcorpo.mcp.opensearch.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.QueryConstraint;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexPattern;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.platform.resource.QueryScope;

class QueryScopeIndexScopeProviderTest {

    private static QueryScope scope(List<String> granted, List<String> link) {
        return QueryScope.of(new QueryConstraint("OpenSearch::Index",
                link == null ? Optional.empty() : Optional.of(new TreeSet<>(link)), "OpenSearch::Index",
                new TreeSet<>(granted)));
    }

    private static IndexScope scopeOf(List<String> granted, List<String> link, String... configured) {
        return QueryScopeIndexScopeProvider.scopeOf(scope(granted, link), List.of(configured));
    }

    @Test
    void aGrantOfPAndTheExpressionOfThePatternNeverReachPaymentsOld() {
        IndexScope scope = scopeOf(List.of("p"), null, "p", "q");

        assertThat(scope.prefixes()).containsExactly("p");
        assertThat(scope.expressionFor(new IndexPattern("p", true))).hasValue("p,p-*");
        assertThat(scope.covers("p")).isTrue();
        assertThat(scope.covers("p-2026.10")).isTrue();
        assertThat(scope.covers("payments-old")).isFalse();
        assertThat(scope.covers("pp")).isFalse();
        assertThat(scope.expressionFor(new IndexPattern("pay", true))).isEmpty();
    }

    @Test
    void theScopeExpressionOfTheStarterIsAPairPerPrefixAndNeverPStar() {
        QueryScope scope = scope(List.of("p", "logs-team-a"), null);

        assertThat(scope.opensearchIndexExpression()).isEqualTo("logs-team-a,logs-team-a-*,p,p-*");
    }

    @Test
    void noGrantMeansAnEmptyScopeAndNotTheConfiguredPrefixes() {
        IndexScope scope = scopeOf(List.of(), null, "p");

        assertThat(scope.isEmpty()).isTrue();
        assertThat(scope.covers("p")).isFalse();
        assertThat(scope.expressionFor(new IndexPattern("p", true))).isEmpty();
    }

    @Test
    void aLinkAndGrantsThatDoNotMeetLeaveNothing() {
        assertThat(scopeOf(List.of("logs-team-a"), List.of("logs-team-b"), "logs-team-a", "logs-team-b").isEmpty()).isTrue();
    }

    @Test
    void aLinkNarrowsTheGrantsToTheLongerPrefixOfAPair() {
        assertThat(scopeOf(List.of("logs"), List.of("logs-team-a"), "logs").prefixes()).containsExactly("logs-team-a");
    }

    @Test
    void grantsOutsideTheConfiguredPrefixesAreDropped() {
        assertThat(scopeOf(List.of("logs-team-a", "other"), null, "logs-team-a").prefixes()).containsExactly("logs-team-a");
        assertThat(scopeOf(List.of("other"), null, "logs-team-a").isEmpty()).isTrue();
    }

    @Test
    void aGrantBroaderThanTheConfigurationLeavesTheConfiguredPrefix() {
        assertThat(scopeOf(List.of("logs"), null, "logs-team-a", "app").prefixes()).containsExactly("logs-team-a");
    }
}
