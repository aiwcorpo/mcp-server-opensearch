package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.manifest.DefinitionHash;
import pl.aiwcorpo.mcp.manifest.ManifestRules;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The C6 manifest at {@code GET /mcp-manifest}: it passes the schema and rules the gateway applies at registration, and
 * its tool definitions are exactly what {@code tools/list} publishes, so the {@code definitionHash} the gateway pins is
 * the one of the tools clients see.
 */
class ManifestTest extends ServerTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET();
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body, Map<String, String> headers, String session) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        if (session != null) {
            request.header("Mcp-Session-Id", session);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode manifest() throws Exception {
        HttpResponse<String> response = get("/mcp-manifest", GATEWAY.service().mint().headers());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private List<JsonNode> listedTools() throws Exception {
        HttpResponse<String> initialize = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                "capabilities":{},"clientInfo":{"name":"manifest-test","version":"1"}}}""",
                GATEWAY.user().mint().headers(), null);
        assertThat(initialize.statusCode()).isEqualTo(200);
        String session = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", GATEWAY.user().mint().headers(), session);
        String body = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}", GATEWAY.user().mint().headers(), session).body();
        int data = body.indexOf("data:");
        JsonNode message = JSON.readTree(data >= 0 ? body.substring(data + 5).strip() : body);
        List<JsonNode> tools = new ArrayList<>();
        message.at("/result/tools").forEach(tools::add);
        return tools;
    }

    @Test
    void theManifestPassesTheRulesOfTheGateway() throws Exception {
        JsonNode manifest = manifest();

        assertThat(ManifestRules.violations(manifest.toString())).isEmpty();
        assertThat(manifest.get("serverId").asString()).isEqualTo(SERVER_ID);
        assertThat(manifest.get("system").asString()).isEqualTo(SYSTEM);
    }

    @Test
    void theDefinitionsAreExactlyWhatToolsListPublishesSoTheHashesAgree() throws Exception {
        JsonNode manifest = manifest();
        List<JsonNode> listed = listedTools();

        assertThat(listed).hasSameSizeAs(manifest.get("tools")).hasSize(3);
        for (JsonNode tool : manifest.get("tools")) {
            String name = tool.get("name").asString();
            JsonNode published = listed.stream().filter(t -> t.get("name").asString().equals(name)).findFirst().orElseThrow();
            assertThat(tool.get("definition")).as(name).isEqualTo(published);
            assertThat(DefinitionHash.ofManifestTool(manifest.toString(), name)).as(name)
                    .isEqualTo(tool.get("definitionHash").asString());
        }
    }

    @Test
    void everyToolHasAPolishTitleAndAClosedInputSchema() throws Exception {
        for (JsonNode tool : manifest().get("tools")) {
            assertThat(tool.at("/definition/title").asString()).isNotBlank();
            assertThat(tool.at("/definition/inputSchema/additionalProperties").asBoolean(true)).isFalse();
            assertThat(tool.get("risk").asString()).isEqualTo("READ_ONLY");
        }
    }

    @Test
    void onlyTheIndexOfGetDocumentIsAResourceRef() throws Exception {
        for (JsonNode tool : manifest().get("tools")) {
            if (tool.get("name").asString().equals("get_document")) {
                assertThat(tool.get("resourceRefs")).hasSize(1);
                assertThat(tool.at("/resourceRefs/0/argument").asString()).isEqualTo("index");
                assertThat(tool.at("/resourceRefs/0/type").asString()).isEqualTo("OpenSearch::Index");
            } else {
                assertThat(tool.path("resourceRefs")).as(tool.get("name").asString()).isEmpty();
            }
        }
    }

    private static JsonNode withoutHash(JsonNode tools) {
        JsonNode copy = tools.deepCopy();
        copy.forEach(tool -> ((tools.jackson.databind.node.ObjectNode) tool).remove("definitionHash"));
        return copy;
    }

    @Test
    void theBuildManifestHasTheToolsTheServerPublishesAndAcceptsOnlyTokenExchange() throws Exception {
        JsonNode exported = JSON.readTree(ManifestExport.manifest());
        JsonNode served = manifest();

        // definitionHash is left out: it covers the accepted credential types too, which tests set to one type.
        assertThat(withoutHash(exported.get("tools"))).as("same definitions as tools/list").isEqualTo(withoutHash(served.get("tools")));
        assertThat(exported.get("resourceTypes")).isEqualTo(served.get("resourceTypes"));
        assertThat(exported.get("serverId")).isEqualTo(served.get("serverId"));
        assertThat(exported.at("/credential/acceptedTypes")).hasSize(1);
        assertThat(exported.at("/credential/acceptedTypes/0").asString()).isEqualTo("token-exchange");
        assertThat(exported.has("environment")).as("only the devstack profile publishes environment=dev (CC-138)").isFalse();
    }

    @Test
    void theManifestNeedsTheInternalJwtAndIsNotATool() throws Exception {
        assertThat(get("/mcp-manifest", Map.of()).statusCode()).isEqualTo(401);
        assertThat(get("/mcp-manifest", GATEWAY.user().tool("get_document").mint().headers()).statusCode()).isEqualTo(401);
    }
}
