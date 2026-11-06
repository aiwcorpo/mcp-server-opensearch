package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.opensearch.core.domain.ResolvedIndex;
import pl.aiwcorpo.mcp.opensearch.core.port.out.IndexClassifier;
import pl.aiwcorpo.mcp.opensearch.tools.OpenSearchIndexResolver;
import pl.aiwcorpo.mcp.platform.test.PolicyDecisions;
import pl.aiwcorpo.mcp.platform.test.PolicyDecisions.Decision;

/**
 * The decisions of the policies in {@code mcp/policies/} on indices, without Spring and without the gateway. The
 * resources come from the resolver of the server, so the scopes an index carries are the ones production derives from
 * its name. The rule for restricted indices comes from the platform, generated from the classification declared for
 * {@code OpenSearch::Index}; the choice of the tool by group is the gateway's and is not tested here.
 */
class OpenSearchPolicyDecisionsTest {

    private static final String READ = "mcp-opensearch-read";
    private static final String WRITE = "mcp-opensearch-write";
    private static final String DESTRUCTIVE = "mcp-opensearch-destructive";
    private static final String TEAM_A = "mcp-opensearch-idx-logs-team-a";

    private final PolicyDecisions policies = PolicyDecisions.fromResources()
            .credentialType("token-exchange")
            .scopeMapping(Map.of(
                    TEAM_A, List.of("OpenSearch::Index/logs-team-a"),
                    "mcp-opensearch-idx-logs-team-b", List.of("OpenSearch::Index/logs-team-b")));

    private static ResolvedResource index(String name, String... tags) {
        IndexClassifier classifier = ignored -> List.of(tags);
        return new OpenSearchIndexResolver(null, classifier).describe(new ResolvedIndex(name));
    }

    @Test
    void anIndexUnderAGrantedPrefixIsReadAndTheIndexNamedLikeThePrefixToo() {
        Decision decision = policies.decide(PolicyDecisions.caller(READ, TEAM_A), "read", index("logs-team-a-2026.10", "logs"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.determiningPolicies()).containsExactly("opensearch-read-in-scope");
        policies.assertAllowed(PolicyDecisions.caller(READ, TEAM_A), "read", index("logs-team-a"));
    }

    @Test
    void aPrefixDoesNotReachTheIndexOfAnotherTeamOrANameThatOnlyStartsTheSame() {
        policies.assertDenied(PolicyDecisions.caller(READ, TEAM_A), "read", index("logs-team-ab", "logs"));
        policies.assertDenied(PolicyDecisions.caller(READ, TEAM_A), "read", index("logs-team-b", "logs"));
        policies.assertDenied(PolicyDecisions.caller(READ, TEAM_A), "read", index("logs-team", "logs"));
    }

    @Test
    void aGrantOfAShorterPrefixCoversTheLongerNames() {
        PolicyDecisions broad = PolicyDecisions.fromResources()
                .scopeMapping(Map.of("mcp-opensearch-idx-logs", List.of("OpenSearch::Index/logs")));

        broad.assertAllowed(PolicyDecisions.caller(READ, "mcp-opensearch-idx-logs"), "read", index("logs-team-a-2026.10"));
        broad.assertDenied(PolicyDecisions.caller(READ, "mcp-opensearch-idx-logs"), "read", index("logstash-1"));
    }

    @Test
    void theReadGroupAloneReadsNothingAndTheScopeAloneReadsNothing() {
        policies.assertDenied(PolicyDecisions.caller(READ), "read", index("logs-team-a-1"));
        policies.assertDenied(PolicyDecisions.caller(TEAM_A), "read", index("logs-team-a-1"));
        policies.assertDenied(PolicyDecisions.caller(WRITE, TEAM_A), "read", index("logs-team-a-1"));
    }

    @Test
    void anIndexTaggedRestrictedIsDeniedByThePlatformRuleWhateverTheGroups() {
        Decision decision = policies.decide(PolicyDecisions.caller(READ, WRITE, DESTRUCTIVE, TEAM_A), "read",
                index("logs-team-a-audit", "logs", "restricted"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.determiningPolicies()).isNotEmpty().allMatch(id -> id.startsWith("platform-"));
        for (String action : List.of("update", "delete")) {
            assertThat(policies.decide(PolicyDecisions.caller(READ, WRITE, DESTRUCTIVE, TEAM_A), action,
                    index("logs-team-a-audit", "restricted")).allowed()).as(action).isFalse();
        }
    }

    @Test
    void theTagIsNormalizedBeforeThePlatformRuleLooksAtIt() {
        policies.assertDenied(PolicyDecisions.caller(READ, TEAM_A), "read", index("logs-team-a", "Restricted"));
        policies.assertDenied(PolicyDecisions.caller(READ, TEAM_A), "read", index("logs-team-a", " RESTRICTED ", "logs"));
    }

    @Test
    void otherTagsAndNoTagsDoNotRestrict() {
        policies.assertAllowed(PolicyDecisions.caller(READ, TEAM_A), "read", index("logs-team-a-1", "logs", "pii-review"));
        policies.assertAllowed(PolicyDecisions.caller(READ, TEAM_A), "read", index("logs-team-a-1"));
    }

    @Test
    void anUpdateNeedsTheWriteGroupAndTheScope() {
        Decision decision = policies.decide(PolicyDecisions.caller(WRITE, TEAM_A), "update", index("logs-team-a", "logs"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.determiningPolicies()).containsExactly("opensearch-update-index-in-scope");
        policies.assertDenied(PolicyDecisions.caller(WRITE, TEAM_A), "update", index("logs-team-ab", "logs"));
        policies.assertDenied(PolicyDecisions.caller(READ, TEAM_A), "update", index("logs-team-a", "logs"));
        policies.assertDenied(PolicyDecisions.caller(WRITE), "update", index("logs-team-a", "logs"));
    }

    @Test
    void aDeleteNeedsTheDestructiveGroupAndTheScope() {
        Decision decision = policies.decide(PolicyDecisions.caller(DESTRUCTIVE, TEAM_A), "delete", index("logs-team-a-old", "logs"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.determiningPolicies()).containsExactly("opensearch-delete-index-in-scope");
        policies.assertDenied(PolicyDecisions.caller(WRITE, TEAM_A), "delete", index("logs-team-a-old"));
        policies.assertDenied(PolicyDecisions.caller(DESTRUCTIVE, TEAM_A), "delete", index("logs-team-b-old"));
    }

    @Test
    void anIndexOutsideTheDeclaredPatternIsRefused() {
        assertThatThrownBy(() -> policies.decide(PolicyDecisions.caller(READ, TEAM_A), "read", index(".security")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policies.decide(PolicyDecisions.caller(READ, TEAM_A), "read", index("Logs-Team-A")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anIndexWithoutTheRequiredTagsAttributeIsRefusedBeforeAnyPolicyRuns() {
        ResolvedResource untagged = ResolvedResource.of("OpenSearch::Index", "logs-team-a", Map.of("name", "logs-team-a"))
                .withScopes(List.of("OpenSearch::Index/logs-team-a"));

        assertThatThrownBy(() -> policies.decide(PolicyDecisions.caller(READ, TEAM_A), "read", untagged))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPrefixThatEndsInADotIsNotAScopeOfTheIndex() {
        // "logs." is not a prefix that can be granted; the builder would refuse a scope that does not match.
        ResolvedResource dotted = index("logs.-x", "logs");

        assertThat(dotted.scopes()).containsExactly("OpenSearch::Index/logs.-x");
        policies.assertDenied(PolicyDecisions.caller(READ, TEAM_A), "read", dotted);
    }

    @Test
    void anOverrideOfTheAdministratorClosesReadsOfOnePrefix() {
        PolicyDecisions closed = policies.override("override-close-team-a",
                "forbid (principal, action == OpenSearch::Action::\"read\", resource)"
                        + " when { resource.scopes.contains(ResourceScope::\"OpenSearch::Index/logs-team-a\") };");

        closed.assertDenied(PolicyDecisions.caller(READ, TEAM_A), "read", index("logs-team-a", "logs"));
    }
}
