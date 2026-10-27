package pl.aiwcorpo.mcp.opensearch.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * OpenSearch 2.x in tests: a real HTTP server on loopback answering the three endpoints the adapter uses with
 * response shapes of the OpenSearch REST API ({@code _resolve/index}, {@code _search}, {@code _doc}).
 *
 * <p>Cluster content (the server is configured with prefixes {@code logs-team-a} and {@code app}):
 * <ul>
 *   <li>{@code logs-team-a-2026.10.26}, {@code logs-team-a-2026.10.27}: readable log indices;</li>
 *   <li>{@code logs-team-ab-1}: a neighbour the prefix {@code logs-team-a} must not cover;</li>
 *   <li>{@code secret-hr-1}: outside every prefix;</li>
 *   <li>{@code logs-team-a-restricted}: exists, the caller has no permission: 403 when named, skipped by
 *       wildcards (OpenSearch Security with {@code do_not_fail_on_forbidden});</li>
 *   <li>{@code logs-team-a-hidden}: an index with {@code index.hidden=true};</li>
 *   <li>alias {@code logs-team-a-all} -> {@code logs-team-a-2026.10.27};</li>
 *   <li>under {@code app}: alias {@code app-leak} -> {@code secret-hr-1}, alias {@code app-two} -> both team-a
 *       indices, data stream {@code app-stream};</li>
 *   <li>an index expression containing {@code boom}: 500 echoing the caller's Authorization header.</li>
 * </ul>
 * The search answer deliberately ignores the {@code _index} filter and {@code _source} includes and also returns
 * a hit of {@code secret-hr-1} with extra fields, so the tests see what the server itself drops. Any request
 * with {@link #EXPIRED_CREDENTIAL} is answered 401, like a revoked token.
 */
final class StubBackend {

    static final String EXPIRED_CREDENTIAL = "Bearer expired-token-of-jkowalski";

    private static final List<String> INDICES = List.of("logs-team-a-2026.10.26", "logs-team-a-2026.10.27",
            "logs-team-ab-1", "secret-hr-1", "logs-team-a-restricted", "logs-team-a-hidden");
    private static final Set<String> HIDDEN = Set.of("logs-team-a-hidden");
    private static final Set<String> FORBIDDEN = Set.of("logs-team-a-restricted");
    private static final Map<String, List<String>> ALIASES = Map.of(
            "logs-team-a-all", List.of("logs-team-a-2026.10.27"),
            "app-leak", List.of("secret-hr-1"),
            "app-two", List.of("logs-team-a-2026.10.26", "logs-team-a-2026.10.27"));
    private static final List<String> DATA_STREAMS = List.of("app-stream");

    /** One request as OpenSearch saw it. */
    record Request(String method, String path, String query, String authorization, String body) {
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private StubBackend(HttpServer server) {
        this.server = server;
    }

    static StubBackend start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            StubBackend backend = new StubBackend(server);
            server.createContext("/", backend::handle);
            server.start();
            return backend;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Authorization headers received so far, in order. */
    List<String> authorizations() {
        return requests.stream().map(Request::authorization).toList();
    }

    void reset() {
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestURI().getQuery(),
                String.valueOf(authorization), body));
        if (EXPIRED_CREDENTIAL.equals(authorization)) {
            respond(exchange, 401, "{\"error\":{\"type\":\"security_exception\",\"reason\":\"token expired\"},\"status\":401}");
        } else if (path.contains("boom")) {
            respond(exchange, 500, "{\"error\":\"boom\",\"seen\":\"" + authorization + "\"}");
        } else if (path.startsWith("/_resolve/index/")) {
            resolve(exchange, path.substring("/_resolve/index/".length()));
        } else if (path.endsWith("/_search") && "POST".equals(exchange.getRequestMethod())) {
            search(exchange, path.substring(1, path.length() - "/_search".length()));
        } else if (path.contains("/_doc/") && "GET".equals(exchange.getRequestMethod())) {
            String[] parts = path.substring(1).split("/_doc/", 2);
            document(exchange, parts[0], parts[1]);
        } else {
            respond(exchange, 400, "{\"error\":\"unsupported in stub\"}");
        }
    }

    private void resolve(HttpExchange exchange, String expression) throws IOException {
        Set<String> indices = new LinkedHashSet<>();
        boolean forbidden = false;
        Set<String> aliases = new LinkedHashSet<>();
        Set<String> streams = new LinkedHashSet<>();
        for (String part : expression.split(",")) {
            boolean wildcard = part.endsWith("*");
            String base = wildcard ? part.substring(0, part.length() - 1) : part;
            for (String index : INDICES) {
                // expand_wildcards=open: a wildcard never reaches hidden indices, an exact name does. Like OpenSearch
                // Security with do_not_fail_on_forbidden, a wildcard skips forbidden indices and a name gets 403.
                if (wildcard && index.startsWith(base) && !HIDDEN.contains(index) && !FORBIDDEN.contains(index)) {
                    indices.add(index);
                } else if (!wildcard && index.equals(base)) {
                    forbidden |= FORBIDDEN.contains(index);
                    indices.add(index);
                }
            }
            ALIASES.keySet().stream().filter(a -> wildcard ? a.startsWith(base) : a.equals(base)).forEach(aliases::add);
            DATA_STREAMS.stream().filter(d -> wildcard ? d.startsWith(base) : d.equals(base)).forEach(streams::add);
        }
        if (forbidden) {
            respond(exchange, 403, "{\"error\":{\"type\":\"security_exception\",\"reason\":\"no permissions for"
                    + " [indices:admin/resolve/index]\"},\"status\":403}");
            return;
        }
        String indicesJson = indices.stream().map(i -> "{\"name\":\"" + i + "\",\"attributes\":[\"open\""
                + (HIDDEN.contains(i) ? ",\"hidden\"" : "") + "]}").collect(Collectors.joining(","));
        String aliasesJson = aliases.stream().map(a -> "{\"name\":\"" + a + "\",\"indices\":["
                + ALIASES.get(a).stream().map(i -> "\"" + i + "\"").collect(Collectors.joining(",")) + "]}")
                .collect(Collectors.joining(","));
        String streamsJson = streams.stream().map(d -> "{\"name\":\"" + d + "\",\"backing_indices\":[\".ds-" + d
                + "-000001\"],\"timestamp_field\":\"@timestamp\"}").collect(Collectors.joining(","));
        respond(exchange, 200, "{\"indices\":[" + indicesJson + "],\"aliases\":[" + aliasesJson
                + "],\"data_streams\":[" + streamsJson + "]}");
    }

    private void search(HttpExchange exchange, String expression) throws IOException {
        List<String> hits = new ArrayList<>();
        hits.add(hit("logs-team-a-2026.10.27", "Xk3n8ZIBdLrT0q1aHf2p", "2026-10-27T09:15:02.123Z",
                "payment failed <script>alert(1)</script> & retried"));
        hits.add(hit("logs-team-a-2026.10.26", "Ab0n8ZIBdLrT0q1aHf00", "2026-10-26T23:59:59.000Z", "error: disk full"));
        hits.add(hit("secret-hr-1", "hr-1", "2026-10-27T09:00:00.000Z", "salary list"));
        respond(exchange, 200, "{\"took\":3,\"timed_out\":false,\"_shards\":{\"total\":2,\"successful\":2,\"skipped\":0,"
                + "\"failed\":0},\"hits\":{\"max_score\":null,\"hits\":[" + String.join(",", hits) + "]}}");
    }

    private static String hit(String index, String id, String timestamp, String message) {
        return "{\"_index\":\"" + index + "\",\"_id\":\"" + id + "\",\"_score\":null,\"_source\":{\"@timestamp\":\""
                + timestamp + "\",\"message\":\"" + message + "\",\"log\":{\"level\":\"ERROR\"},"
                + "\"user\":{\"password\":\"not-for-the-model\"}},\"sort\":[1761556502123]}";
    }

    private void document(HttpExchange exchange, String index, String id) throws IOException {
        if (FORBIDDEN.contains(index)) {
            respond(exchange, 403, "{\"error\":{\"type\":\"security_exception\"},\"status\":403}");
        } else if (!INDICES.contains(index)) {
            respond(exchange, 404, "{\"error\":{\"type\":\"index_not_found_exception\",\"index\":\"" + index
                    + "\"},\"status\":404}");
        } else if ("Xk3n8ZIBdLrT0q1aHf2p".equals(id)) {
            respond(exchange, 200, "{\"_index\":\"" + index + "\",\"_id\":\"" + id + "\",\"_version\":1,\"_seq_no\":0,"
                    + "\"_primary_term\":1,\"found\":true,\"_source\":{\"@timestamp\":\"2026-10-27T09:15:02.123Z\","
                    + "\"message\":\"<b>payment</b> failed\",\"service\":{\"name\":\"billing\"},"
                    + "\"user\":{\"password\":\"not-for-the-model\"}}}");
        } else {
            respond(exchange, 404, "{\"_index\":\"" + index + "\",\"_id\":\"" + id + "\",\"found\":false}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
