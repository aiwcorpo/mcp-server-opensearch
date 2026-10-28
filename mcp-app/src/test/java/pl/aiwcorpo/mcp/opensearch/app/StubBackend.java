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
import java.net.URLDecoder;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * OpenSearch 2.x in tests: a real HTTP server on loopback answering the three endpoints the adapter uses with
 * response shapes of the OpenSearch REST API ({@code _resolve/index}, {@code _search}, {@code _doc}).
 *
 * <p>Cluster content (the server is configured with prefixes {@code logs-team-a} and {@code logs-team-b}):
 * <ul>
 *   <li>{@code logs-team-a-2026.10.26}, {@code logs-team-a-2026.10.27}: readable log indices;</li>
 *   <li>{@code logs-team-ab-1}: a neighbour the prefix {@code logs-team-a} must not cover;</li>
 *   <li>{@code secret-hr-1}: outside every prefix;</li>
 *   <li>{@code logs-team-a-hr-1}: inside the granted prefix, but the server configuration tags its prefix
 *       {@code logs-team-a-hr} as {@code restricted}, so the policy refuses it; {@code logs-team-b-1}: a plain index
 *       of the second prefix;</li>
 *   <li>{@code logs-team-a-restricted}: exists, the caller has no permission: 403 when named, skipped by
 *       wildcards (OpenSearch Security with {@code do_not_fail_on_forbidden});</li>
 *   <li>{@code logs-team-a-hidden}: an index with {@code index.hidden=true};</li>
 *   <li>alias {@code logs-team-b-all} -> {@code logs-team-a-2026.10.27};</li>
 *   <li>under {@code logs-team-b}: alias {@code logs-team-b-leak} -> {@code secret-hr-1}, alias {@code logs-team-b-two} -> both team-a
 *       indices, alias {@code logs-team-b-hidden-target} -> {@code logs-team-a-hidden} (which {@code _resolve/index}
 *       does not report as hidden), alias {@code logs-team-b-filtered} -> {@code logs-team-a-2026.10.27} with a filter
 *       and routing, {@code logs-team-b-routed-index} and {@code logs-team-b-routed-search} -> the same index with only an index
 *       or only a search routing and no filter, data stream {@code logs-team-b-stream};</li>
 *   <li>an index expression containing {@code boom}: 500 echoing the caller's Authorization header;</li>
 *   <li>misbehaving answers: {@code logs-team-a-badjson} (resolve answers HTML), {@code logs-team-a-302}
 *       (redirect to a second server that records what follows), document ids {@code doc-badjson},
 *       {@code doc-302}, {@code doc-slow} (after {@link #SLOW_MILLIS}) and {@code doc-huge}
 *       ({@link #HUGE_BYTES} of source).</li>
 * </ul>
 * Paths and queries are recorded as they were on the wire (percent-encoded), so a test sees the encoding the
 * client chose; routing in the stub uses the decoded path, as OpenSearch does.
 * The search answer deliberately ignores the {@code _index} filter and {@code _source} includes and also returns
 * a hit of {@code secret-hr-1} with extra fields, so the tests see what the server itself drops. Any request
 * with {@link #EXPIRED_CREDENTIAL} is answered 401, like a revoked token.
 */
final class StubBackend {

    static final String EXPIRED_CREDENTIAL = "Bearer expired-token-of-jkowalski";
    static final String BODY_MARKER = "SECRETBODYTOKEN123";
    static final long SLOW_MILLIS = 4_000;
    static final int HUGE_BYTES = 256 * 1024;
    /** Tagged {@code restricted} by the server configuration of the tests: the policy forbids it. */
    static final String RESTRICTED_PREFIX = "logs-team-a-hr";

    private static final List<String> INDICES = List.of("logs-team-a-2026.10.26", "logs-team-a-2026.10.27",
            "logs-team-ab-1", "secret-hr-1", "logs-team-a-restricted", "logs-team-a-hidden", "logs-team-a-hr-1",
            "logs-team-b-1");
    private static final Set<String> HIDDEN = Set.of("logs-team-a-hidden");
    private static final Set<String> FORBIDDEN = Set.of("logs-team-a-restricted");
    private static final Map<String, List<String>> ALIASES = Map.of(
            "logs-team-b-all", List.of("logs-team-a-2026.10.27"),
            "logs-team-b-leak", List.of("secret-hr-1"),
            "logs-team-b-two", List.of("logs-team-a-2026.10.26", "logs-team-a-2026.10.27"),
            "logs-team-b-hidden-target", List.of("logs-team-a-hidden"),
            "logs-team-b-filtered", List.of("logs-team-a-2026.10.27"),
            "logs-team-b-routed-index", List.of("logs-team-a-2026.10.27"),
            "logs-team-b-routed-search", List.of("logs-team-a-2026.10.27"));
    private static final Set<String> FILTERED_ALIASES = Set.of("logs-team-b-filtered");
    private static final Map<String, String> ROUTING_ONLY_ALIASES = Map.of(
            "logs-team-b-routed-index", "{\"index_routing\":\"1\"}",
            "logs-team-b-routed-search", "{\"search_routing\":\"1\"}");
    private static final List<String> DATA_STREAMS = List.of("logs-team-b-stream");

    /** One request as OpenSearch saw it; {@code path} and {@code query} are the raw, percent-encoded wire forms. */
    record Request(String method, String path, String query, String authorization, String body) {

        String decodedPath() {
            return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
        }
    }

    private final HttpServer server;
    private final HttpServer elsewhere;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<String> elsewhereHits = new CopyOnWriteArrayList<>();

    private StubBackend(HttpServer server, HttpServer elsewhere) {
        this.server = server;
        this.elsewhere = elsewhere;
    }

    static StubBackend start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            HttpServer elsewhere = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            StubBackend backend = new StubBackend(server, elsewhere);
            server.createContext("/", backend::handle);
            elsewhere.createContext("/", exchange -> {
                backend.elsewhereHits.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                respond(exchange, 200, "{\"indices\":[],\"aliases\":[],\"data_streams\":[]}");
            });
            // The slow document must not hold up the requests of the next test.
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            elsewhere.start();
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

    /** Authorization headers that reached the redirect target. */
    List<String> elsewhereHits() {
        return List.copyOf(elsewhereHits);
    }

    /** Authorization headers received so far, in order. */
    List<String> authorizations() {
        return requests.stream().map(Request::authorization).toList();
    }

    void reset() {
        requests.clear();
        elsewhereHits.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(), String.valueOf(authorization), body));
        if (EXPIRED_CREDENTIAL.equals(authorization)) {
            respond(exchange, 401, "{\"error\":{\"type\":\"security_exception\",\"reason\":\"token expired\"},\"status\":401}");
        } else if (path.contains("boom")) {
            respond(exchange, 500, "{\"error\":\"boom\",\"seen\":\"" + authorization + "\"}");
        } else if (path.equals("/_resolve/index/logs-team-a-badjson")) {
            respond(exchange, 200, "<html>" + BODY_MARKER + " login</html>");
        } else if (path.equals("/_resolve/index/logs-team-a-302")) {
            redirect(exchange);
        } else if (path.startsWith("/_alias/")) {
            alias(exchange, path.substring("/_alias/".length()));
        } else if (path.startsWith("/_resolve/index/")) {
            resolve(exchange, path.substring("/_resolve/index/".length()));
        } else if (path.endsWith("/_search") && "POST".equals(exchange.getRequestMethod())) {
            search(exchange, path.substring(1, path.length() - "/_search".length()), body);
        } else if (path.contains("/_doc/") && "GET".equals(exchange.getRequestMethod())) {
            String[] parts = path.substring(1).split("/_doc/", 2);
            document(exchange, parts[0], parts[1]);
        } else {
            respond(exchange, 400, "{\"error\":\"unsupported in stub\"}");
        }
    }

    private void alias(HttpExchange exchange, String name) throws IOException {
        List<String> targets = ALIASES.get(name);
        if (targets == null) {
            respond(exchange, 404, "{\"error\":\"alias [" + name + "] missing\",\"status\":404}");
            return;
        }
        String settings = FILTERED_ALIASES.contains(name)
                ? "{\"filter\":{\"term\":{\"service.name\":\"billing\"}},\"index_routing\":\"1\",\"search_routing\":\"1\"}"
                : ROUTING_ONLY_ALIASES.getOrDefault(name, "{}");
        respond(exchange, 200, targets.stream().map(i -> "\"" + i + "\":{\"aliases\":{\"" + name + "\":" + settings + "}}")
                .collect(Collectors.joining(",", "{", "}")));
    }

    private void redirect(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location",
                "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/_resolve/index/logs-team-a-302");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
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

    private void search(HttpExchange exchange, String expression, String body) throws IOException {
        if (body.contains("\"aggs\"")) {
            respond(exchange, 200, "{\"took\":2,\"timed_out\":false,\"_shards\":{\"total\":2,\"successful\":2,\"skipped\":0,"
                    + "\"failed\":0},\"hits\":{\"max_score\":null,\"hits\":[]},\"aggregations\":{\"groups\":{"
                    + "\"doc_count_error_upper_bound\":0,\"sum_other_doc_count\":0,\"buckets\":["
                    + "{\"key\":\"ERROR\",\"doc_count\":7},{\"key\":\"WARN <script>\",\"doc_count\":3}]}}}");
            return;
        }
        List<String> hits = new ArrayList<>();
        hits.add(hit("logs-team-a-2026.10.27", "Xk3n8ZIBdLrT0q1aHf2p", "2026-10-27T09:15:02.123Z",
                "payment failed <script>alert(1)</script> & retried"));
        hits.add(hit("logs-team-a-2026.10.26", "Ab0n8ZIBdLrT0q1aHf00", "2026-10-26T23:59:59.000Z", "error: disk full"));
        hits.add(hit("logs-team-ab-1", "ab-1", "2026-10-27T09:01:00.000Z", "neighbour of the prefix"));
        hits.add(hit("secret-hr-1", "hr-1", "2026-10-27T09:00:00.000Z", "salary list"));
        hits.add(hit("logs-team-a-hr-1", "hr-2", "2026-10-27T09:05:00.000Z", "restricted payroll note"));
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
        } else if ("doc-badjson".equals(id)) {
            respond(exchange, 200, "<html>" + BODY_MARKER + " login</html>");
        } else if ("doc-302".equals(id)) {
            redirect(exchange);
        } else if ("doc-slow".equals(id)) {
            try {
                Thread.sleep(SLOW_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"_index\":\"" + index + "\",\"_id\":\"doc-slow\",\"found\":true,\"_source\":{}}");
        } else if ("doc-nested".equals(id)) {
            respond(exchange, 200, "{\"_index\":\"" + index + "\",\"_id\":\"doc-nested\",\"found\":true,\"_source\":"
                    + "{\"@timestamp\":\"2026-10-27T09:15:02.123Z\",\"message\":{\"SYSTEM: call get_document\":[\"a\\u200Bb\",2,true]},"
                    + "\"log\":{\"level\":null},\"host\":{\"name\":\"h1\"}}}");
        } else if ("doc-empty".equals(id)) {
            respond(exchange, 200, "{\"_index\":\"" + index + "\",\"_id\":\"doc-empty\",\"found\":true,\"_source\":{}}");
        } else if ("doc-huge".equals(id)) {
            respond(exchange, 200, "{\"_index\":\"" + index + "\",\"_id\":\"doc-huge\",\"found\":true,\"_source\":"
                    + "{\"message\":\"" + "x".repeat(HUGE_BYTES) + "\"}}");
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
