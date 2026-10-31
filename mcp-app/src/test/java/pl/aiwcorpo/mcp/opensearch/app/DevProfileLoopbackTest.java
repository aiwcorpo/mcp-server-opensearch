package pl.aiwcorpo.mcp.opensearch.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import pl.aiwcorpo.mcp.platform.test.DevProfileGuardAssertions;

/**
 * The dev profile (test keys, no mTLS) does not listen on an address other machines can reach: the rule is the
 * starter's, and the server's own context must have the guard that applies it.
 */
class DevProfileLoopbackTest extends ServerTestSupport {

    @Autowired
    ApplicationContext context;

    @Test
    void devListensOnLoopbackOnly() {
        DevProfileGuardAssertions.assertDevProfileLoopbackRule();
        DevProfileGuardAssertions.assertServerIsGuarded(context);
    }
}
