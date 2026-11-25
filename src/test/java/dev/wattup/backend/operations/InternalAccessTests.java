package dev.wattup.backend.operations;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class InternalAccessTests {
    private static final String READ = "r".repeat(43);
    private static final String WRITE = "w".repeat(43);
    private final InternalAccess access = new InternalAccess(new JsonMapper(), "127.0.0.1,::1", READ, WRITE);

    @Test
    void requiresNetworkAndTokenAndDoesNotTrustForwardedHeader() throws Exception {
        assertDenied("GET", "192.0.2.5", WRITE, 403);
        assertDenied("GET", "127.0.0.1", "fake-token", 401);
        assertDenied("POST", "127.0.0.1", READ, 403);
        assertThat(access.preHandle(request("GET", "127.0.0.1", READ), new MockHttpServletResponse(), new Object()))
                .isTrue();
        assertThat(access.preHandle(request("POST", "::1", WRITE), new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void disablesInternalOperationsWithoutCredentials() throws Exception {
        var disabled = new InternalAccess(new JsonMapper(), "127.0.0.1", "", "");
        var response = new MockHttpServletResponse();
        assertThat(disabled.preHandle(request("GET", "127.0.0.1", READ), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    private void assertDenied(String method, String remote, String token, int status) throws Exception {
        var response = new MockHttpServletResponse();
        assertThat(access.preHandle(request(method, remote, token), response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentAsString()).doesNotContain(READ, WRITE);
    }

    private static MockHttpServletRequest request(String method, String remote, String token) {
        var request = new MockHttpServletRequest(method, "/internal/collector/runs");
        request.setRemoteAddr(remote);
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        return request;
    }
}
