package pl.aiwcorpo.mcp.opensearch.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.QueryConstraint;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexScope;
import pl.aiwcorpo.mcp.platform.resource.QueryScope;

class QueryScopeIndexScopeProviderTest {

    private static QueryScope scope(List<String> granted) {
        return QueryScope.of(new QueryConstraint("OpenSearch::Index", new TreeSet<>(granted)));
    }

    private static IndexScope scopeOf(List<String> granted, String... configured) {
        return QueryScopeIndexScopeProvider.scopeOf(scope(granted), List.of(configured));
    }

    @Test
    void aGrantOfPAndTheExpressionOfThePatternNeverReachPaymentsOld() {
        IndexScope scope = scopeOf(List.of("p"), "p", "q");

        assertThat(scope.prefixes()).containsExactly("p");
        assertThat(scope.covers("p")).isTrue();
        assertThat(scope.covers("p-2026.10")).isTrue();
        assertThat(scope.covers("payments-old")).isFalse();
        assertThat(scope.covers("pp")).isFalse();
    }

    @Test
    void theScopeExpressionOfTheStarterIsAPairPerPrefixAndNeverPStar() {
        QueryScope scope = scope(List.of("p", "logs-team-a"));

        assertThat(scope.opensearchIndexExpression()).isEqualTo("logs-team-a,logs-team-a-*,p,p-*");
    }

    @Test
    void noGrantMeansAnEmptyScopeAndNotTheConfiguredPrefixes() {
        IndexScope scope = scopeOf(List.of(), "p");

        assertThat(scope.isEmpty()).isTrue();
        assertThat(scope.covers("p")).isFalse();
    }

    @Test
    void grantsOutsideTheConfiguredPrefixesAreDropped() {
        assertThat(scopeOf(List.of("logs-team-a", "other"), "logs-team-a").prefixes()).containsExactly("logs-team-a");
        assertThat(scopeOf(List.of("other"), "logs-team-a").isEmpty()).isTrue();
    }

    @Test
    void aGrantBroaderThanTheConfigurationLeavesTheConfiguredPrefix() {
        assertThat(scopeOf(List.of("logs"), "logs-team-a", "app").prefixes()).containsExactly("logs-team-a");
    }
}
