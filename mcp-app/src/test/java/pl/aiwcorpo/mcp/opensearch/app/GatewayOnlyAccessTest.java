package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/** Only the gateway gets in: no internal JWT, or one not meant for this call, is 401 and never anonymous. */
class GatewayOnlyAccessTest extends ServerTestSupport {

    private static final String GET_RECORD = """
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_document",
            "arguments":{"index":"logs-team-a-2026.10.27","id":"Xk3n8ZIBdLrT0q1aHf2p"}}}""";

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
            "capabilities":{},"clientInfo":{"name":"claim-test","version":"1"}}}""";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void withoutJwtEveryMcpRequestIs401() throws Exception {
        HttpResponse<String> call = post(GET_RECORD, Map.of("X-MCP-Credential", "Bearer stolen-pat",
                "X-MCP-Credential-Type", CREDENTIAL_TYPE));
        assertThat(call.statusCode()).isEqualTo(401);
        assertThat(call.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(call.body()).contains("\"error\":\"unauthenticated\"").doesNotContain("stolen-pat");

        assertThat(post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", Map.of()).statusCode()).isEqualTo(401);
        assertThat(post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                "capabilities":{},"clientInfo":{"name":"intruder","version":"1"}}}""", Map.of()).statusCode())
                .isEqualTo(401);
        assertThat(BACKEND.authorizations()).isEmpty();
    }

    @Test
    void aTokenForAnotherServerToolOrRequestIsRejected() throws Exception {
        TestGateway.Minted otherServer = GATEWAY.user().tool("get_document").audience(anotherServerId()).mint();
        assertThat(post(GET_RECORD, otherServer.headers()).statusCode()).isEqualTo(401);

        TestGateway.Minted otherTool = GATEWAY.user().tool("search_logs").mint();
        assertThat(post(GET_RECORD, otherTool.headers()).statusCode()).isEqualTo(401);

        TestGateway.Minted service = GATEWAY.service().mint();
        assertThat(post(GET_RECORD, service.headers()).statusCode()).isEqualTo(401);

        TestGateway.Minted expired = GATEWAY.user().tool("get_document")
                .issuedAt(Instant.now().minusSeconds(300)).mint();
        assertThat(post(GET_RECORD, expired.headers()).statusCode()).isEqualTo(401);
        assertThat(BACKEND.authorizations()).isEmpty();
    }

    @Test
    void aTokenWithTheClaimsOfTheRemovedVirtualServersIsRejected() throws Exception {
        // Positive control: the same kind of token without the extra claim passes the guard, so the 401 is about the claim
        // (the starter logs it as internal_jwt_rejected reason=invalid_claims).
        assertThat(post(INITIALIZE, GATEWAY.user().mint().headers()).statusCode()).isEqualTo(200);
        for (String claim : new String[] {"vs", "vsc"}) {
            TestGateway.Minted withClaim = GATEWAY.user().extraClaim(claim, "abc-only").mint();

            assertThat(post(INITIALIZE, withClaim.headers()).statusCode()).as(claim).isEqualTo(401);
        }
        assertThat(BACKEND.authorizations()).isEmpty();
    }

    @Test
    void aTokenOfAnotherPurposeCannotPushPolicyDataNorCallAndAnUnheardOfRevisionIsStale() throws Exception {
        String push = "/.well-known/mcp-policy-data";
        assertThat(postTo(push, "{}", GATEWAY.service().mint().headers()).statusCode()).as("a call token pushing").isEqualTo(401);
        assertThat(postTo(push, "{}", GATEWAY.service().use("manifest").mint().headers()).statusCode()).as("a token of the removed purpose manifest pushing").isEqualTo(401);
        assertThat(post(GET_RECORD, GATEWAY.service().use(TestGateway.USE_POLICY_DATA).mint().headers()).statusCode())
                .as("a policy-data token calling").isEqualTo(401);

        HttpResponse<String> stale = post(GET_RECORD, GATEWAY.user().tool("get_document").policyRevision(Integer.MAX_VALUE).mint().headers());
        assertThat(stale.statusCode()).as("the server has not heard of that revision yet").isEqualTo(409);
        assertThat(stale.body()).contains("\"error\":\"policy_data_stale\"").contains("\"requiredRevision\":" + Integer.MAX_VALUE);
        assertThat(BACKEND.authorizations()).isEmpty();
    }

    @Test
    void onlyTheHealthEndpointAnswersWithoutJwt() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(401);
    }

    /** A real platform server id that is not this one (the template generates each of them). */
    private static String anotherServerId() {
        return SERVER_ID.equals("mcp-gitlab") ? "mcp-confluence" : "mcp-gitlab";
    }

    private HttpResponse<String> post(String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postTo(String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
