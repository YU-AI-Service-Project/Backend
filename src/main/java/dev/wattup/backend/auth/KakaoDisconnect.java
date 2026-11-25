package dev.wattup.backend.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
class KakaoDisconnect {
    private static final String ENDPOINT = "https://kapi.kakao.com/v1/user/unlink";
    private final RestTemplate http;
    private final JsonMapper json;
    private final String adminKey;

    @Autowired
    KakaoDisconnect(JsonMapper json, @Value("${wattup.auth.kakao-admin-key:}") String adminKey) {
        this(json, adminKey, SocialIdentityVerifier.client());
    }

    KakaoDisconnect(JsonMapper json, String adminKey, RestTemplate http) {
        this.json = json;
        this.adminKey = adminKey;
        this.http = http;
    }

    void unlink(String subject) {
        if (!adminKey.matches("[A-Za-z0-9_-]+")) {
            throw new AuthFailure(503, "AUTH_UNAVAILABLE");
        }
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "KakaoAK " + adminKey);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        var body = new LinkedMultiValueMap<String, String>();
        body.add("target_id_type", "user_id");
        body.add("target_id", subject);
        try {
            String response = http.postForObject(ENDPOINT, new HttpEntity<>(body, headers), String.class);
            var parsed = response == null ? null : json.readTree(response);
            if (parsed == null || !parsed.path("id").isIntegralNumber() || !subject.equals(parsed.path("id").asText())) {
                throw unavailable();
            }
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 400 || !alreadyDisconnected(e.getResponseBodyAsString())) {
                throw unavailable();
            }
        } catch (RestClientException | JacksonException e) {
            throw unavailable();
        }
    }

    private boolean alreadyDisconnected(String response) {
        try {
            var parsed = json.readTree(response);
            return parsed != null && parsed.path("code").asInt() == -101;
        } catch (JacksonException e) {
            return false;
        }
    }

    private static AuthFailure unavailable() {
        return new AuthFailure(502, "UPSTREAM_UNAVAILABLE");
    }

}
