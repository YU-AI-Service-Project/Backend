package dev.wattup.backend.auth;

import dev.wattup.backend.WattUpApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "WATTUP_TEST_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthApiTests {
    private static final String PASSWORD = "SyntheticPassphrase-1";
    private static final String CHANGED = "SyntheticPassphrase-2";
    private static final String RESET = "SyntheticPassphrase-3";
    private final JsonMapper json = new JsonMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String fixture = "Auth" + UUID.randomUUID().toString().substring(0, 8);
    private final Map<String, String> codes = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> resetTokens = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<Map<String, Object>> consents = List.of(
            Map.of("type", "TERMS", "version", fixture, "agreed", true),
            Map.of("type", "PRIVACY", "version", fixture, "agreed", true));
    private ConfigurableApplicationContext application;
    private JdbcTemplate db;
    private int port;

    @BeforeAll
    void launch() {
        String url = System.getenv("WATTUP_TEST_DB_URL");
        if (!url.matches("jdbc:mysql://[^/?#]+/wattup_test(?:\\?[^#]*)?")) {
            throw new IllegalArgumentException("WATTUP_TEST_DB_URL must point to wattup_test");
        }
        String testKey = Base64.getEncoder().encodeToString(new byte[32]);
        application = new SpringApplicationBuilder(WattUpApplication.class, Stubs.class).run(
                "--spring.datasource.url=" + url, "--wattup.collection.enabled=false",
                "--server.port=0", "--server.address=127.0.0.1", "--wattup.auth.jwt-secret=" + testKey,
                "--wattup.auth.email-secret=" + testKey);
        db = application.getBean(JdbcTemplate.class);
        port = application.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        for (String type : List.of("TERMS", "PRIVACY")) {
            db.update("""
                    INSERT INTO agreement_policies (policy_type,policy_version,title,is_required,is_current,published_at)
                    VALUES (?, ?, ?, TRUE, TRUE, UTC_TIMESTAMP(3))
                    """, type, fixture, "Synthetic fixture policy");
        }
        var mail = application.getBean("stubMail", AuthMail.class);
        doAnswer(call -> codes.put(call.getArgument(0), call.getArgument(1))).when(mail).verification(anyString(), anyString());
        doAnswer(call -> resetTokens.put(call.getArgument(0), call.getArgument(1))).when(mail).reset(anyString(), anyString());
        var provider = application.getBean("stubVerifier", SocialIdentityVerifier.class);
        when(provider.verify("KAKAO", "synthetic-kakao")).thenReturn(new SocialIdentityVerifier.Identity(
                "KAKAO", fixture, address("social"), "Test member", "https://example.test/member.png"));
        when(provider.verify("GOOGLE", "synthetic-google")).thenReturn(new SocialIdentityVerifier.Identity(
                "GOOGLE", fixture, address("social"), "Test member", null));
        when(provider.verify("KAKAO", "rejected")).thenThrow(new SocialIdentityVerifier.Failure(false));
        when(provider.verify("GOOGLE", "unavailable")).thenThrow(new SocialIdentityVerifier.Failure(true));
    }

    @AfterAll
    void cleanup() {
        try {
            if (db != null) {
                db.update("DELETE FROM users WHERE nickname LIKE ?", fixture + "%");
                db.update("DELETE FROM auth_email_challenges WHERE email LIKE ?", "%." + fixture.toLowerCase(java.util.Locale.ROOT)
                        + "@example.test");
                db.update("DELETE FROM auth_action_tickets WHERE provider_user_id = ? OR email LIKE ?", fixture,
                        "%." + fixture.toLowerCase(java.util.Locale.ROOT) + "@example.test");
                db.update("DELETE FROM agreement_policies WHERE policy_version = ?", fixture);
                db.update("DELETE FROM auth_request_buckets WHERE bucket_key IN (?,?,?)",
                        AuthValues.hash("signup-ip|127.0.0.1"), AuthValues.hash("social-ip|127.0.0.1"),
                        AuthValues.hash("refresh-ip|127.0.0.1"));
            }
        } finally {
            if (application != null) {
                application.close();
            }
            http.close();
        }
    }

    @Test
    void socialSignupUsesLatestConsentAndNeverLinksOnMatchingEmail() throws Exception {
        assertThat(send("GET", "/api/auth/agreements", null, null, 200).path("agreements").size()).isEqualTo(2);
        assertCode(send("POST", "/api/auth/social/kakao", Map.of("providerAccessToken", "rejected"), null, 401),
                "INVALID_PROVIDER_TOKEN");
        assertCode(send("POST", "/api/auth/social/google", Map.of("idToken", "unavailable"), null, 502),
                "UPSTREAM_UNAVAILABLE");
        String registration = send("POST", "/api/auth/social/kakao", Map.of("providerAccessToken", "synthetic-kakao"),
                null, 202).path("registrationToken").asText();
        assertCode(send("POST", "/api/auth/social/signup",
                Map.of("registrationToken", registration, "nickname", fixture + "k", "agreements", List.of()), null, 403),
                "CONSENT_REQUIRED");
        var signup = Map.of("registrationToken", registration, "nickname", fixture + "k", "agreements", consents);
        var created = send("POST", "/api/auth/social/signup", signup, null, 201);
        assertThat(created.path("user").path("providers").get(0).asText()).isEqualTo("KAKAO");
        assertCode(send("POST", "/api/auth/social/signup", signup, null, 410), "REGISTRATION_EXPIRED");
        var login = send("POST", "/api/auth/social/kakao", Map.of("providerAccessToken", "synthetic-kakao"), null, 200);
        String otherRegistration = send("POST", "/api/auth/social/google", Map.of("idToken", "synthetic-google"),
                null, 202).path("registrationToken").asText();
        var google = send("POST", "/api/auth/social/signup",
                Map.of("registrationToken", otherRegistration, "nickname", fixture + "g", "agreements", consents), null, 201);
        assertThat(google.path("user").path("userId").asLong()).isNotEqualTo(created.path("user").path("userId").asLong());

        var first = created.path("tokens");
        var second = login.path("tokens");
        var secondRefresh = Map.of("refreshToken", second.path("refreshToken").asText());
        assertCode(send("POST", "/api/auth/logout", secondRefresh, first.path("accessToken").asText(), 401),
                "REFRESH_TOKEN_INVALID");

        var original = Map.of("refreshToken", first.path("refreshToken").asText());
        var rotated = send("POST", "/api/auth/refresh", original, null, 200);
        assertThat(rotated.path("refreshToken").asText()).isNotEqualTo(first.path("refreshToken").asText());
        assertCode(send("POST", "/api/auth/refresh", original, null, 401), "SESSION_REVOKED");
        send("POST", "/api/auth/logout", Map.of("refreshToken", rotated.path("refreshToken").asText()),
                rotated.path("accessToken").asText(), 401);
        send("POST", "/api/auth/logout", secondRefresh, second.path("accessToken").asText(), 204);
        assertCode(send("POST", "/api/auth/refresh", secondRefresh, null, 401), "SESSION_REVOKED");

        send("PATCH", "/api/users/me/password", Map.of("currentPassword", PASSWORD, "newPassword", CHANGED),
                google.path("tokens").path("accessToken").asText(), 422);
    }

    @Test
    void emailSignupLoginPasswordChangeAndResetRevokeSessions() throws Exception {
        String email = address("registered");
        var request = Map.of("email", email.toUpperCase(java.util.Locale.ROOT), "purpose", "SIGNUP");
        send("POST", "/api/auth/email/verifications", request, null, 202);
        assertCode(send("POST", "/api/auth/email/verifications", request, null, 429), "RATE_LIMITED");
        String code = codes.get(email);
        assertThat(code).matches("[0-9]{6}");
        var confirmation = Map.of("email", email, "purpose", "SIGNUP", "code", code);
        String verification = send("POST", "/api/auth/email/verifications/confirm", confirmation, null, 200)
                .path("verificationToken").asText();
        send("POST", "/api/auth/email/verifications/confirm", confirmation, null, 410);
        var signup = Map.of("email", email, "password", PASSWORD, "nickname", fixture + "e",
                "verificationToken", verification, "agreements", consents);
        var created = send("POST", "/api/auth/signup/email", signup, null, 201);
        assertThat(created.path("user").path("providers").size()).isZero();
        send("POST", "/api/auth/signup/email", signup, null, 410);

        db.update("DELETE FROM auth_request_buckets WHERE bucket_key = ?", AuthValues.hash("verification-email|" + email));
        assertThat(codes.remove(email)).isEqualTo(code);
        send("POST", "/api/auth/email/verifications", request, null, 202);
        assertThat(codes).doesNotContainKey(email);
        assertCode(send("POST", "/api/auth/email/verifications/confirm", confirmation, null, 400),
                "INVALID_VERIFICATION_CODE");

        var login = send("POST", "/api/auth/login/email", Map.of("email", email, "password", PASSWORD), null, 200);
        var tokens = login.path("tokens");
        send("PATCH", "/api/users/me/password", Map.of("currentPassword", PASSWORD, "newPassword", CHANGED),
                tokens.path("accessToken").asText(), 204);
        assertCode(send("POST", "/api/auth/refresh", Map.of("refreshToken", tokens.path("refreshToken").asText()),
                null, 401), "SESSION_REVOKED");
        send("POST", "/api/auth/logout-all", null, created.path("tokens").path("accessToken").asText(), 401);
        var beforeReset = send("POST", "/api/auth/login/email", Map.of("email", email, "password", CHANGED), null, 200);

        var known = send("POST", "/api/auth/password-resets", Map.of("email", email), null, 202);
        var missing = send("POST", "/api/auth/password-resets", Map.of("email", address("missing")), null, 202);
        assertThat(known).isEqualTo(missing);
        assertThat(resetTokens).containsKey(email).doesNotContainKey(address("missing"));
        String earlierReset = resetTokens.get(email);
        db.update("DELETE FROM auth_request_buckets WHERE bucket_key = ?", AuthValues.hash("reset-email|" + email));
        send("POST", "/api/auth/password-resets", Map.of("email", email), null, 202);
        String newerReset = resetTokens.get(email);
        assertThat(newerReset).isNotEqualTo(earlierReset);
        var reset = Map.of("resetToken", earlierReset, "newPassword", RESET);
        send("POST", "/api/auth/password-resets/confirm", reset, null, 204);
        send("POST", "/api/auth/password-resets/confirm", reset, null, 410);
        send("POST", "/api/auth/password-resets/confirm", Map.of("resetToken", newerReset, "newPassword", RESET), null, 410);
        send("POST", "/api/auth/logout-all", null, beforeReset.path("tokens").path("accessToken").asText(), 401);
        assertCode(send("POST", "/api/auth/login/email", Map.of("email", email, "password", PASSWORD), null, 401),
                "INVALID_CREDENTIALS");

        var device = send("POST", "/api/auth/login/email", Map.of("email", email, "password", RESET), null, 200).path("tokens");
        var other = send("POST", "/api/auth/login/email", Map.of("email", email, "password", RESET), null, 200).path("tokens");
        send("POST", "/api/auth/logout-all", null, device.path("accessToken").asText(), 204);
        send("POST", "/api/auth/logout-all", null, other.path("accessToken").asText(), 401);
    }

    @Test
    void limitsIncorrectEmailCodesAndKeepsGuestApiPublic() throws Exception {
        String email = address("limited");
        send("POST", "/api/auth/email/verifications", Map.of("email", email, "purpose", "SIGNUP"), null, 202);
        String correct = codes.get(email);
        assertThat(correct).matches("[0-9]{6}");
        var wrong = Map.of("email", email, "purpose", "SIGNUP", "code", correct.equals("111111") ? "222222" : "111111");
        for (int attempt = 0; attempt < 4; attempt++) {
            assertCode(send("POST", "/api/auth/email/verifications/confirm", wrong, null, 400),
                    "INVALID_VERIFICATION_CODE");
        }
        assertCode(send("POST", "/api/auth/email/verifications/confirm", wrong, null, 429), "TOO_MANY_ATTEMPTS");
        send("POST", "/api/auth/email/verifications/confirm", Map.of("email", email, "purpose", "SIGNUP", "code", correct),
                null, 429);
        send("GET", "/api/recommendations?latitude=0&longitude=0&profile=ALL", null, "invalid-guest-token", 200);
        send("GET", "/api/charging-stations?latitude=0&longitude=0", null, null, 200);
        send("PATCH", "/api/users/me/password", Map.of("currentPassword", PASSWORD, "newPassword", CHANGED), null, 401);
    }

    @Test
    void concurrentRefreshRevokesOnlyTheReusedFamilyAndChecksExpiry() throws Exception {
        String nickname = fixture + "r";
        db.update("INSERT INTO users (nickname) VALUES (?)", nickname);
        long userId = db.queryForObject("SELECT user_id FROM users WHERE nickname = ?", Long.class, nickname);
        var sessions = application.getBean(AuthSessions.class);
        var first = sessions.start(userId, null);
        var other = sessions.start(userId, null);
        String initial = json.writeValueAsString(Map.of("refreshToken", first.refreshToken()));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/refresh"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(initial)).build();
        var left = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var right = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var replies = List.of(left.get(), right.get());
        assertThat(replies).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 401);
        var rotated = json.readTree(replies.stream().filter(reply -> reply.statusCode() == 200).findFirst().orElseThrow().body());
        assertCode(send("POST", "/api/auth/refresh", Map.of("refreshToken", rotated.path("refreshToken").asText()),
                null, 401), "SESSION_REVOKED");
        db.update("""
                UPDATE user_refresh_sessions SET issued_at = UTC_TIMESTAMP(3) - INTERVAL 2 DAY,
                    expires_at = UTC_TIMESTAMP(3) - INTERVAL 1 DAY WHERE refresh_token_hash = ? AND revoked_at IS NULL
                """, AuthValues.hash(other.refreshToken()));
        assertCode(send("POST", "/api/auth/refresh", Map.of("refreshToken", other.refreshToken()), null, 401),
                "REFRESH_TOKEN_EXPIRED");
    }

    private String address(String type) {
        return type + "." + fixture.toLowerCase(java.util.Locale.ROOT) + "@example.test";
    }

    private JsonNode send(String method, String path, Map<String, ?> body, String access, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(12));
        if (access != null) {
            request.header("Authorization", "Bearer " + access);
        }
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        var result = http.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).as("%s %s", method, path).isEqualTo(expected);
        return result.body().isBlank() ? json.createObjectNode() : json.readTree(result.body());
    }

    private static void assertCode(JsonNode result, String code) {
        assertThat(result.path("error").path("code").asText()).isEqualTo(code);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Stubs {
        @Bean
        @Primary
        AuthMail stubMail() {
            return mock(AuthMail.class);
        }

        @Bean
        @Primary
        SocialIdentityVerifier stubVerifier() {
            return mock(SocialIdentityVerifier.class);
        }
    }
}
