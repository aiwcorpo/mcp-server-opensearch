package pl.aiwcorpo.mcp.opensearch.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The backend system in tests: a real HTTP server on loopback, so the adapter runs with its real client,
 * timeouts and error mapping. Records it knows:
 * <ul>
 *   <li>{@code ABC-1}: visible, with an extra field the server must not pass on;</li>
 *   <li>{@code ABC-403}: exists but the caller may not see it (403);</li>
 *   <li>{@code ABC-500}: the backend fails and echoes the caller's Authorization header in the body;</li>
 *   <li>anything else: 404.</li>
 * </ul>
 * Any request with {@link #EXPIRED_CREDENTIAL} is answered 401, like a revoked token.
 */
final class StubBackend {

    static final String EXPIRED_CREDENTIAL = "Bearer expired-pat-of-jkowalski";

    private final HttpServer server;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    private StubBackend(HttpServer server) {
        this.server = server;
    }

    static StubBackend start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            StubBackend backend = new StubBackend(server);
            server.createContext("/api/records/", backend::handle);
            server.start();
            return backend;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Authorization headers received so far, in order. */
    List<String> authorizations() {
        return List.copyOf(authorizations);
    }

    void reset() {
        authorizations.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        authorizations.add(String.valueOf(authorization));
        String id = exchange.getRequestURI().getPath().substring("/api/records/".length());
        if (EXPIRED_CREDENTIAL.equals(authorization)) {
            respond(exchange, 401, "{\"message\":\"token expired\"}");
        } else if ("ABC-1".equals(id)) {
            respond(exchange, 200, """
                    {"id":"ABC-1","project":"ABC","title":"Quarterly report","status":"open",
                     "internalNotes":"not for the model"}""");
        } else if ("ABC-403".equals(id)) {
            respond(exchange, 403, "{\"message\":\"forbidden\"}");
        } else if ("ABC-500".equals(id)) {
            respond(exchange, 500, "{\"error\":\"boom\",\"seen\":\"" + authorization + "\"}");
        } else {
            respond(exchange, 404, "{\"message\":\"no such record\"}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
