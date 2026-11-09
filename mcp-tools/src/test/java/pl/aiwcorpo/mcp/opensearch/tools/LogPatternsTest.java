package pl.aiwcorpo.mcp.opensearch.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogPatterns;

class LogPatternsTest {

    @Test
    void masksNumbersHexIdsUuidsAddressesAndTimestamps() {
        assertThat(LogPatterns.mask("2026-10-27T09:15:02.123Z payment 4711 failed for order 98765"))
                .isEqualTo("<TS> payment <NUM> failed for order <NUM>");
        assertThat(LogPatterns.mask("2026-10-27 09:15:02,123 retry in 2.5 s")).isEqualTo("<TS> retry in <NUM> s");
        assertThat(LogPatterns.mask("session 3f2b8c1e-9a4d-4e7b-8c55-0d6f1a2b3c4d closed"))
                .isEqualTo("session <UUID> closed");
        assertThat(LogPatterns.mask("connect to 10.20.30.40:9200 refused from 192.168.1.7"))
                .isEqualTo("connect to <IP> refused from <IP>");
        assertThat(LogPatterns.mask("trace 4bf92f3577b34da6a3ce929d0e0e4736 span 0xdeadBEEF"))
                .isEqualTo("trace <HEX> span <HEX>");
    }

    @Test
    void leavesWordsAndIdentifiersWithDigitsInsideTheNameAlone() {
        assertThat(LogPatterns.mask("feedback and deadbeef stay words, node-3 and utf8 stay names"))
                .isEqualTo("feedback and deadbeef stay words, node-<NUM> and utf8 stay names");
    }

    @Test
    void collapsesWhitespaceSoLineBreaksDoNotSplitAPattern() {
        assertThat(LogPatterns.mask("  disk   full\n  on /data  ")).isEqualTo("disk full on /data");
    }

    @Test
    void groupsByPatternBiggestFirstWithTheFirstMessageAsExample() {
        LogPatterns.Summary summary = LogPatterns.summarize(List.of(
                "timeout after 30 ms", "disk full", "timeout after 45 ms", "timeout after 51 ms", "disk full",
                "rare problem 1"), 2);

        assertThat(summary.groups()).extracting(LogPatterns.Group::pattern, LogPatterns.Group::count,
                LogPatterns.Group::example)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("timeout after <NUM> ms", 3L, "timeout after 30 ms"),
                        org.assertj.core.groups.Tuple.tuple("disk full", 2L, "disk full"));
        assertThat(summary.others()).isEqualTo(1);
        assertThat(summary.otherMessages()).isEqualTo(1);
    }

    @Test
    void anEmptySampleHasNoPatterns() {
        LogPatterns.Summary summary = LogPatterns.summarize(List.of(" ", ""), 10);

        assertThat(summary.groups()).isEmpty();
        assertThat(summary.others()).isZero();
    }
}
