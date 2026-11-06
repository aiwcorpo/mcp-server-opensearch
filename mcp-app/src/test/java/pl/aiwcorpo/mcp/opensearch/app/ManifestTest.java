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
    void theManifestCarriesThePoliciesAndTheDeclarationsOfTheJarWithTheirHash() throws Exception {
        JsonNode manifest = manifest();

        assertThat(manifest.at("/policies/namespace").asString()).isEqualTo("OpenSearch");
        assertThat(manifest.at("/policies/files").findValuesAsString("name")).containsExactly("read.cedar", "update.cedar");
        assertThat(manifest.get("policiesHash").asString()).startsWith("sha256:");
        assertThat(manifest.get("resourceTypes")).hasSize(1);
        JsonNode index = manifest.at("/resourceTypes/0");
        assertThat(index.get("type").asString()).isEqualTo("OpenSearch::Index");
        assertThat(index.at("/classification/attribute").asString()).isEqualTo("tags");
        assertThat(index.at("/classification/kind").asString()).isEqualTo("set");
        assertThat(index.has("earlyScopeCheck") && index.get("earlyScopeCheck").asBoolean()).isFalse();
    }

    @Test
    void theDefinitionsAreExactlyWhatToolsListPublishesSoTheHashesAgree() throws Exception {
        JsonNode manifest = manifest();
        List<JsonNode> listed = listedTools();

        assertThat(listed).hasSameSizeAs(manifest.get("tools")).hasSize(9);
        for (JsonNode tool : manifest.get("tools")) {
            String name = tool.get("name").asString();
            JsonNode published = listed.stream().filter(t -> t.get("name").asString().equals(name)).findFirst().orElseThrow();
            assertThat(tool.get("definition")).as(name).isEqualTo(published);
            assertThat(DefinitionHash.ofManifestTool(manifest.toString(), name)).as(name)
                    .isEqualTo(tool.get("definitionHash").asString());
        }
    }

    @Test
    void everyToolHasATitleAndAClosedInputSchema() throws Exception {
        for (JsonNode tool : manifest().get("tools")) {
            assertThat(tool.at("/definition/title").asString()).isNotBlank();
            assertThat(tool.at("/definition/inputSchema/additionalProperties").asBoolean(true)).isFalse();
            assertThat(tool.get("risk").asString()).isEqualTo("READ_ONLY");
        }
    }

    @Test
    void onlyTheTwoClusterToolsCarryTheOpsGroupAsAdditionalGroup() throws Exception {
        for (JsonNode tool : manifest().get("tools")) {
            String name = tool.get("name").asString();
            if (List.of("get_cluster_health", "get_backend_info").contains(name)) {
                assertThat(tool.get("additionalGroup").asString()).as(name).isEqualTo("mcp-opensearch-ops");
            } else {
                assertThat(tool.has("additionalGroup")).as(name).isFalse();
            }
        }
    }

    @Test
    void onlyTheIndexOfTheToolsThatReadOneIndexIsAResourceRef() throws Exception {
        for (JsonNode tool : manifest().get("tools")) {
            if (List.of("get_document", "get_index_mapping").contains(tool.get("name").asString())) {
                assertThat(tool.get("resourceRefs")).hasSize(1);
                assertThat(tool.at("/resourceRefs/0/argument").asString()).isEqualTo("index");
                assertThat(tool.at("/resourceRefs/0/type").asString()).isEqualTo("OpenSearch::Index");
            } else {
                assertThat(tool.path("resourceRefs")).as(tool.get("name").asString()).isEmpty();
            }
        }
    }

    /** The version in the parent pom, which is what the manifest must carry (the surefire working directory is mcp-app). */
    private static String projectVersion() throws java.io.IOException {
        String pom = java.nio.file.Files.readString(java.nio.file.Path.of("..", "pom.xml"));
        java.util.regex.Matcher version = java.util.regex.Pattern
                .compile("<artifactId>mcp-opensearch-parent</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
        assertThat(version.find()).as("version in the parent pom").isTrue();
        return version.group(1);
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

        // The hash covers the accepted credential types. The tests run with the types they configure, the production
        // configuration of a system may list others; the hashes are comparable only when the lists are equal.
        boolean sameTypes = exported.at("/credential/acceptedTypes").equals(served.at("/credential/acceptedTypes"));
        if (sameTypes) {
            assertThat(exported.get("tools")).as("same definitions and definitionHash as tools/list").isEqualTo(served.get("tools"));
        } else {
            assertThat(withoutHash(exported.get("tools"))).as("same definitions as tools/list").isEqualTo(withoutHash(served.get("tools")));
        }
        // The served manifest carries the version the tests configure; the file of the build must carry the one of the project.
        assertThat(exported.get("version").asString()).as("version in the build manifest").isEqualTo(projectVersion());
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
