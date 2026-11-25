package dev.wattup.backend.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class ProviderResponseLimit implements ClientHttpResponse {
    private static final int MAX_BYTES = 256 * 1024;
    private final ClientHttpResponse response;
    private InputStream body;

    ProviderResponseLimit(ClientHttpResponse response) {
        this.response = response;
    }

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
        if (body == null) {
            body = new FilterInputStream(response.getBody()) {
                private int remaining = MAX_BYTES;

                @Override
                public int read() throws IOException {
                    int value = in.read();
                    if (value >= 0) {
                        accountFor(1);
                    }
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = in.read(bytes, offset, Math.min(length, remaining + 1));
                    if (count > 0) {
                        accountFor(count);
                    }
                    return count;
                }

                private void accountFor(int count) {
                    remaining -= count;
                    if (remaining < 0) {
                        throw new ResourceAccessException("Identity provider response exceeded the configured size");
                    }
                }
            };
        }
        return body;
    }

    @Override
    public void close() {
        try {
            response.getBody().close();
        } catch (IOException ignored) {
        }
    }
}
