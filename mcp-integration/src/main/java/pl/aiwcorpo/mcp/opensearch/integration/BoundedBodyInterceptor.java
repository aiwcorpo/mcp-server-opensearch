package pl.aiwcorpo.mcp.opensearch.integration;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Fails the read once a response body passes {@code maxBytes}, so a backend cannot exhaust the heap. */
final class BoundedBodyInterceptor implements ClientHttpRequestInterceptor {

    private final long maxBytes;

    BoundedBodyInterceptor(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        return new ClientHttpResponse() {
            @Override
            public HttpStatusCode getStatusCode() throws IOException {
                return response.getStatusCode();
            }

            @Override
            public String getStatusText() throws IOException {
                return response.getStatusText();
            }

            @Override
            public HttpHeaders getHeaders() {
                return response.getHeaders();
            }

            @Override
            public InputStream getBody() throws IOException {
                return new Bounded(response.getBody(), maxBytes);
            }

            @Override
            public void close() {
                response.close();
            }
        };
    }

    private static final class Bounded extends FilterInputStream {

        private long remaining;

        Bounded(InputStream in, long maxBytes) {
            super(in);
            this.remaining = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            remaining -= n;
            if (remaining < 0) {
                throw new IOException("backend response larger than the configured limit");
            }
        }
    }
}
