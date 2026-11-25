package dev.wattup.backend.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KakaoDisconnectTests {
    private static final String URL = "https://kapi.kakao.com/v1/user/unlink";
    private final RestTemplate client = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
    private final KakaoDisconnect disconnect = new KakaoDisconnect(new JsonMapper(), "synthetic-admin-key", client);

    @Test
    void sendsAdminUnlinkForTheStoredSubjectAndAcceptsAlreadyDisconnected() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK synthetic-admin-key"))
                .andExpect(content().string("target_id_type=user_id&target_id=9007199254740993"))
                .andRespond(withSuccess("{\"id\":9007199254740993}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":-101}"));
        disconnect.unlink("9007199254740993");
        disconnect.unlink("9007199254740993");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":8}", "{}", "not-json"})
    void rejectsAnInvalidUnlinkResponse(String reply) {
        server.expect(requestTo(URL)).andRespond(withSuccess(reply, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> disconnect.unlink("7")).isInstanceOfSatisfying(AuthFailure.class,
                failure -> assertThat(failure.code).isEqualTo("UPSTREAM_UNAVAILABLE"));
        server.verify();
    }
}
