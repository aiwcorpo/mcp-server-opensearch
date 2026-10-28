package pl.aiwcorpo.mcp.opensearch.app;

import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.test.DevProfileGuardAssertions;

/**
 * The dev profile (test keys, no mTLS) does not listen on an address other machines can reach. The rule is the
 * starter's; this test keeps it from disappearing unnoticed from the starter version the server builds against.
 */
class DevProfileLoopbackTest {

    @Test
    void devListensOnLoopbackOnly() {
        DevProfileGuardAssertions.assertDevProfileLoopbackRule();
    }
}
