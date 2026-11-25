package dev.wattup.backend.auth;

import com.nimbusds.jose.RemoteKeySourceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
class SocialIdentityVerifier {
    private static final String KAKAO = "https://kapi.kakao.com";
    private static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private final JsonMapper json;
    private final String kakaoApp;
    private final String googleClient;
    private final RestTemplate http;
    private final NimbusJwtDecoder google;

    @Autowired
    SocialIdentityVerifier(JsonMapper json, @Value("${wattup.auth.kakao-app-id:}") String kakaoApp,
                           @Value("${wattup.auth.google-client-id:}") String googleClient) {
        this(json, kakaoApp, googleClient, client());
    }

    SocialIdentityVerifier(JsonMapper json, String kakaoApp, String googleClient, RestTemplate http) {
        this.json = json;
        this.kakaoApp = kakaoApp;
        this.googleClient = googleClient;
        this.http = http;
        this.google = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS).restOperations(http).build();
    }

    Identity verify(String provider, String token) {
        if (token == null || token.isBlank() || token.length() > 8192 || !token.matches("[A-Za-z0-9._~+/-]+=*")) {
            throw new Failure(false);
        }
        return switch (provider) {
            case "KAKAO" -> kakao(token);
            case "GOOGLE" -> google(token);
            default -> throw new Failure(false);
        };
    }

    private Identity kakao(String token) {
        if (!kakaoApp.matches("[1-9][0-9]*")) {
            throw new Failure(true);
        }
        var info = kakaoGet("/v1/user/access_token_info", token);
        String subject = numericId(info.path("id"));
        if (subject == null || !kakaoApp.equals(numericId(info.path("app_id"))) || info.path("expires_in").asInt(-1) <= 0) {
            throw new Failure(false);
        }
        var user = kakaoGet("/v2/user/me", token);
        if (!subject.equals(numericId(user.path("id")))) {
            throw new Failure(false);
        }
        var account = user.path("kakao_account");
        var profile = account.path("profile");
        String email = account.path("is_email_valid").asBoolean(false) && account.path("is_email_verified").asBoolean(false)
                ? account.path("email").asText(null) : null;
        return new Identity("KAKAO", subject, bounded(email, 320), bounded(profile.path("nickname").asText(null), 60),
                bounded(profile.path("profile_image_url").asText(null), 1000));
    }

    private JsonNode kakaoGet(String path, String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        try {
            String body = http.exchange(KAKAO + path, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
            JsonNode parsed = body == null ? null : json.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                throw new Failure(true);
            }
            return parsed;
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            boolean temporary = status >= 500 || status == 429 || (status == 400 && temporaryKakaoError(e));
            throw new Failure(temporary);
        } catch (RestClientException | JacksonException e) {
            throw new Failure(true);
        }
    }

    private boolean temporaryKakaoError(RestClientResponseException response) {
        try {
            return json.readTree(response.getResponseBodyAsString()).path("code").asInt() == -1;
        } catch (JacksonException e) {
            return false;
        }
    }

    private Identity google(String token) {
        if (googleClient.isBlank()) {
            throw new Failure(true);
        }
        try {
            var jwt = google.decode(token);
            var audience = jwt.getAudience();
            String issuer = jwt.getClaimAsString("iss");
            String subject = bounded(jwt.getSubject(), 255);
            if (!List.of("https://accounts.google.com", "accounts.google.com").stream().anyMatch(i -> i.equals(issuer))
                    || audience == null || !audience.contains(googleClient)
                    || (audience.size() > 1 && !googleClient.equals(jwt.getClaimAsString("azp")))
                    || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(Instant.now()) || subject == null) {
                throw new Failure(false);
            }
            return new Identity("GOOGLE", subject,
                    Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")) ? bounded(jwt.getClaimAsString("email"), 320) : null,
                    bounded(jwt.getClaimAsString("name"), 60), bounded(jwt.getClaimAsString("picture"), 1000));
        } catch (JwtException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof RestClientException || cause instanceof RemoteKeySourceException) {
                    throw new Failure(true);
                }
            }
            throw new Failure(false);
        }
    }

    private static String numericId(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.asLong() > 0 ? node.asText() : null;
    }

    private static String bounded(String value, int limit) {
        return value == null || value.isBlank() || value.length() > limit ? null : value;
    }

    static RestTemplate client() {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(2));
        requests.setReadTimeout(Duration.ofSeconds(3));
        return new RestTemplate(requests);
    }

    record Identity(String provider, String subject, String email, String nickname, String profileImageUrl) {
    }

    static final class Failure extends RuntimeException {
        private final boolean unavailable;

        Failure(boolean unavailable) {
            super("Provider verification failed");
            this.unavailable = unavailable;
        }

        boolean unavailable() {
            return unavailable;
        }
    }
}
