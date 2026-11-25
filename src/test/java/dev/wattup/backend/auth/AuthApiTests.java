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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "WATTUP_TEST_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthApiTests {
    private static final String PASSWORD = "SyntheticPassphrase-1";
    private static final String CHANGED = "SyntheticPassphrase-2";
    private static final String RESET = "SyntheticPassphrase-3";
    private static final String OPS_READ = "R".repeat(43);
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
                "--wattup.auth.email-secret=" + testKey, "--wattup.account.withdrawal-enabled=true",
                "--springdoc.api-docs.enabled=true", "--springdoc.swagger-ui.enabled=true",
                "--wattup.internal.read-token=" + OPS_READ);
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
                var rate = application.getBean(AuthRateLimit.class);
                db.update("DELETE FROM auth_request_buckets WHERE bucket_key IN (?,?,?)",
                        rate.key("signup-ip", "127.0.0.1"), rate.key("social-ip", "127.0.0.1"),
                        rate.key("refresh-ip", "127.0.0.1"));
            }
        } finally {
            if (application != null) {
                application.close();
            }
            http.close();
        }
    }

    @Test
    void publicAuthResponsesAreNeverCacheableAndOversizedChunkedJsonIsRejected() throws Exception {
        var response = http.send(HttpRequest.newBuilder(local("/api/auth/agreements")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");

        byte[] payload = ("{\"stationIds\":[\"" + "a".repeat(70_000) + "\"]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var oversized = http.send(HttpRequest.newBuilder(local("/api/predictions/batch"))
                .version(HttpClient.Version.HTTP_1_1).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(oversized.statusCode()).isEqualTo(413);
        assertThat(json.readTree(oversized.body()).path("error").path("code").asText()).isEqualTo("REQUEST_TOO_LARGE");
        byte[] form = ("query=" + "a".repeat(70_000)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var oversizedForm = http.send(HttpRequest.newBuilder(local("/api/recommendations"))
                .version(HttpClient.Version.HTTP_1_1).header("Content-Type", "application/x-www-form-urlencoded")
                .method("PATCH", HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(form))).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(oversizedForm.statusCode()).isEqualTo(413);
    }

    @Test
    void rechecksSessionUnderTheLockBeforeRevokingOtherSessions() {
        var member = account("authority");
        var service = application.getBean(AuthSessions.class);
        var acceptedAccess = service.authenticate(member.tokens.accessToken());
        service.logout(acceptedAccess, member.tokens.refreshToken());
        var newSession = service.start(member.id, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.logoutAll(acceptedAccess))
                .isInstanceOfSatisfying(AuthFailure.class, failure -> assertThat(failure.code).isEqualTo("UNAUTHORIZED"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> application.getBean(AuthEmail.class)
                .change(acceptedAccess, PASSWORD, CHANGED))
                .isInstanceOfSatisfying(AuthFailure.class, failure -> assertThat(failure.code).isEqualTo("UNAUTHORIZED"));
        assertThat(service.authenticate(newSession.accessToken()).getSubject()).isEqualTo(Long.toString(member.id));
    }

    @Test
    void removesExpiredRateLimitBucketsAndKeepsActiveWindows() {
        var rate = application.getBean(AuthRateLimit.class);
        String expired = rate.key("expired", fixture);
        String active = rate.key("active", fixture);
        assertThat(expired).isNotEqualTo(AuthValues.hash("expired|" + fixture));
        assertThat(active).isNotEqualTo(expired);
        db.update("""
                INSERT INTO auth_request_buckets (bucket_key,window_started_at,hit_count)
                VALUES (?,UTC_TIMESTAMP(3) - INTERVAL 3 HOUR,1),(?,UTC_TIMESTAMP(3),1)
                """, expired, active);
        try {
            rate.removeExpired();
            assertThat(db.queryForList("SELECT bucket_key FROM auth_request_buckets WHERE bucket_key IN (?,?)",
                    String.class, expired, active)).containsExactly(active);
        } finally {
            db.update("DELETE FROM auth_request_buckets WHERE bucket_key IN (?,?)", expired, active);
        }
    }

    @Test
    void publishesExactlyTheSpringOperationsFromThe52ItemContract() throws Exception {
        var row = Pattern.compile("^\\| (\\d+) \\| `((?:GET|POST|PATCH|DELETE) [^`]+)` \\|");
        var expected = new HashSet<String>();
        var ids = new HashSet<Integer>();
        for (String line : Files.readAllLines(Path.of("docs/development/PROGRESS.md"))) {
            var match = row.matcher(line);
            if (match.find()) {
                int id = Integer.parseInt(match.group(1));
                assertThat(ids.add(id)).isTrue();
                if (id != 47 && id != 48) {
                    assertThat(expected.add(match.group(2))).isTrue();
                }
            }
        }
        assertThat(ids).hasSize(52).allMatch(id -> id >= 1 && id <= 52);
        var response = http.send(HttpRequest.newBuilder(local("/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var paths = json.readTree(response.body()).path("paths");
        var actual = new HashSet<String>();
        paths.properties().forEach(path -> path.getValue().properties().forEach(method -> {
            if (Set.of("get", "post", "patch", "delete").contains(method.getKey())) {
                actual.add(method.getKey().toUpperCase(java.util.Locale.ROOT) + " " + path.getKey());
            }
        }));
        assertThat(actual).hasSize(50).containsExactlyInAnyOrderElementsOf(expected);
        var report = paths.path("/api/support/reports").path("post");
        assertThat(report.at("/security/0/accessToken").isArray()).isTrue();
        assertThat(report.at("/requestBody/content/application~1json/schema").isObject()).isTrue();
        assertThat(report.at("/responses/201").isObject()).isTrue();
        assertThat(paths.path("/api/auth/social/google").at("/post/responses/202").isObject()).isTrue();
        assertThat(paths.path("/api/auth/logout").at("/post/responses/204/content").isMissingNode()).isTrue();
        assertThat(paths.path("/internal/collector/runs").at("/post/security/0/internalWriteToken").isArray()).isTrue();
        var ui = http.send(HttpRequest.newBuilder(local("/swagger-ui/index.html")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("Swagger UI");
    }

    @Test
    void guardsInternalOperationsThroughTheActualHttpApp() throws Exception {
        var path = local("/internal/collector/health");
        var rejected = http.send(HttpRequest.newBuilder(path).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(rejected.statusCode()).isEqualTo(401);
        var allowed = http.send(HttpRequest.newBuilder(path).header("Authorization", "Bearer " + OPS_READ)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Cache-Control")).hasValue("no-store");
        var forbidden = http.send(HttpRequest.newBuilder(local("/internal/collector/runs"))
                .header("Authorization", "Bearer " + OPS_READ).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(forbidden.statusCode()).isEqualTo(403);
    }

    private URI local(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
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

        db.update("DELETE FROM auth_request_buckets WHERE bucket_key = ?",
                application.getBean(AuthRateLimit.class).key("verification-email", email));
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
        db.update("DELETE FROM auth_request_buckets WHERE bucket_key = ?",
                application.getBean(AuthRateLimit.class).key("reset-email", email));
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

    @Test
    void updatesProfileAndUsesOnlyTheAuthenticatedSessionForSessionManagement() throws Exception {
        var member = account("profile");
        var foreign = account("foreign");
        var other = application.getBean(AuthSessions.class).start(member.id, "อีก기기");
        String access = member.tokens.accessToken();
        send("GET", "/api/users/me", null, null, 401);
        assertThat(send("GET", "/api/users/me", null, access, 200).path("userId").asLong()).isEqualTo(member.id);
        assertCode(send("PATCH", "/api/users/me", Map.of("nickname", "<bad>"), access, 400), "INVALID_NICKNAME");
        assertCode(send("PATCH", "/api/users/me", Map.of("nickname", fixture + "New",
                "profileImageUrl", "https://example.test/invalid"), access, 400), "INVALID_REQUEST");
        assertThat(send("PATCH", "/api/users/me", Map.of("nickname", fixture + "New"), access, 200)
                .path("nickname").asText()).isEqualTo(fixture + "New");
        var items = send("GET", "/api/users/me/sessions", null, access, 200).path("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.toString()).doesNotContain(other.refreshToken(), member.tokens.refreshToken());
        String foreignFamily = sessionId(foreign.tokens);
        send("DELETE", "/api/users/me/sessions/" + foreignFamily, null, access, 404);
        send("DELETE", "/api/users/me/sessions/" + sessionId(other), null, access, 204);
        send("GET", "/api/users/me", null, other.accessToken(), 401);
        send("GET", "/api/users/me", null, access, 200);
        send("GET", "/api/users/me", null, foreign.tokens.accessToken(), 200);
    }

    @Test
    void recordsOptionalConsentAndRequiresPurposeAccountAndSessionBoundTickets() throws Exception {
        var member = account("linked");
        var foreign = account("other");
        var sibling = application.getBean(AuthSessions.class).start(member.id, null);
        String access = member.tokens.accessToken();
        db.update("""
                INSERT INTO agreement_policies (policy_type,policy_version,title,is_required,is_current,published_at)
                VALUES ('OPTIONAL_MARKETING',?,'Synthetic optional policy',FALSE,TRUE,UTC_TIMESTAMP(3))
                """, fixture);
        try {
            assertCode(send("PATCH", "/api/users/me/agreements", Map.of("type", "TERMS", "agreed", false),
                    access, 400), "INVALID_REQUEST");
            for (boolean agreed : new boolean[]{true, false}) {
                assertThat(send("PATCH", "/api/users/me/agreements", Map.of("type", "OPTIONAL_MARKETING", "agreed", agreed),
                        access, 200).path("agreed").asBoolean()).isEqualTo(agreed);
            }
            var decisions = send("GET", "/api/users/me/agreements", null, access, 200).path("items");
            assertThat(decisions.size()).isEqualTo(2);
            assertThat(decisions.get(0).path("agreed").asBoolean()).isFalse();
        } finally {
            db.update("DELETE FROM agreement_policies WHERE policy_type='OPTIONAL_MARKETING' AND policy_version=?", fixture);
        }
        var stub = application.getBean("stubVerifier", SocialIdentityVerifier.class);
        when(stub.verify("KAKAO", "account-link")).thenReturn(new SocialIdentityVerifier.Identity(
                "KAKAO", fixture + "link", address("linked-provider"), null, null));
        String expired = reauth(access, "LINK_SOCIAL");
        db.update("""
                UPDATE auth_action_tickets SET issued_at=UTC_TIMESTAMP(3)-INTERVAL 10 MINUTE,
                    expires_at=UTC_TIMESTAMP(3)-INTERVAL 5 MINUTE WHERE ticket_hash=?
                """, AuthValues.hash(expired));
        assertCode(send("POST", "/api/users/me/social-accounts/kakao",
                Map.of("providerAccessToken", "account-link", "reauthenticationToken", expired), access, 410),
                "REAUTHENTICATION_EXPIRED");
        String link = reauth(access, "LINK_SOCIAL");
        var linkBody = Map.of("providerAccessToken", "account-link", "reauthenticationToken", link);
        send("POST", "/api/users/me/social-accounts/kakao", linkBody, sibling.accessToken(), 403);
        send("POST", "/api/users/me/social-accounts/kakao", linkBody, foreign.tokens.accessToken(), 403);
        send("DELETE", "/api/users/me/social-accounts/kakao", Map.of("reauthenticationToken", link), access, 403);
        var linked = send("POST", "/api/users/me/social-accounts/kakao", linkBody, access, 201).path("items");
        assertThat(linked.get(0).path("provider").asText()).isEqualTo("KAKAO");
        assertThat(linked.get(0).has("providerUserId")).isFalse();
        send("POST", "/api/users/me/social-accounts/kakao", linkBody, access, 403);
        var conflict = Map.of("providerAccessToken", "account-link", "reauthenticationToken",
                reauth(foreign.tokens.accessToken(), "LINK_SOCIAL"));
        assertCode(send("POST", "/api/users/me/social-accounts/kakao", conflict, foreign.tokens.accessToken(), 409),
                "ACCOUNT_LINK_CONFLICT");

        db.update("DELETE FROM user_email_credentials WHERE user_id=?", member.id);
        String unlink = send("POST", "/api/users/me/reauthentication", Map.of("purpose", "UNLINK_SOCIAL", "method", "KAKAO",
                "providerAccessToken", "account-link"), access, 200).path("reauthenticationToken").asText();
        assertCode(send("DELETE", "/api/users/me/social-accounts/kakao", Map.of("reauthenticationToken", unlink),
                access, 409), "LAST_LOGIN_METHOD");
        var disconnect = application.getBean("stubDisconnect", KakaoDisconnect.class);
        verify(disconnect, never()).unlink(fixture + "link");
        addPassword(member.id, address("linked"));
        doThrow(new AuthFailure(502, "UPSTREAM_UNAVAILABLE")).doNothing().when(disconnect).unlink(fixture + "link");
        send("DELETE", "/api/users/me/social-accounts/kakao", Map.of("reauthenticationToken", unlink), access, 502);
        assertThat(send("GET", "/api/users/me/social-accounts", null, access, 200).path("items").size()).isEqualTo(1);
        send("DELETE", "/api/users/me/social-accounts/kakao", Map.of("reauthenticationToken", unlink), access, 204);
        assertThat(send("GET", "/api/users/me/social-accounts", null, access, 200).path("items").size()).isZero();
        send("GET", "/api/users/me", null, sibling.accessToken(), 401);
    }

    @Test
    void withdrawalPurgesPersonalDataAndPreventsOldTicketsFromReopeningTheAccount() throws Exception {
        var member = account("withdrawn");
        String access = member.tokens.accessToken();
        var other = application.getBean(AuthSessions.class).start(member.id, "Second device");
        var provider = application.getBean("stubVerifier", SocialIdentityVerifier.class);
        when(provider.verify("GOOGLE", "withdraw-identity")).thenReturn(new SocialIdentityVerifier.Identity(
                "GOOGLE", fixture + "withdraw", address("withdrawn"), null, null));
        String oldSignup = send("POST", "/api/auth/social/google", Map.of("idToken", "withdraw-identity"), null, 202)
                .path("registrationToken").asText();
        send("POST", "/api/users/me/social-accounts/google",
                Map.of("idToken", "withdraw-identity", "reauthenticationToken", reauth(access, "LINK_SOCIAL")), access, 201);
        String pendingDuringWithdrawal = application.getBean(AuthAccounts.class).ticket("SOCIAL_SIGNUP", null,
                address("withdrawn"), "GOOGLE", fixture + "withdraw", null, Duration.ofMinutes(10));
        String stationId = "KECO:" + fixture;
        db.update("INSERT INTO charging_stations (station_id,source_station_id,station_name) VALUES (?,?,'Fixture')",
                stationId, fixture);
        try {
            db.update("INSERT INTO user_preferences (user_id) VALUES (?)", member.id);
            db.update("INSERT INTO user_favorites (user_id,station_id) VALUES (?,?)", member.id, stationId);
            var confirmation = Map.of("confirmation", "WITHDRAW", "reauthenticationToken", reauth(access, "WITHDRAW"));
            send("DELETE", "/api/users/me", confirmation, other.accessToken(), 403);
            send("DELETE", "/api/users/me", confirmation, access, 204);
            send("GET", "/api/users/me", null, access, 401);
            send("GET", "/api/users/me", null, other.accessToken(), 401);
            send("POST", "/api/auth/refresh", Map.of("refreshToken", other.refreshToken()), null, 401);
            send("POST", "/api/auth/social/signup",
                    Map.of("registrationToken", oldSignup, "nickname", fixture + "Re", "agreements", consents), null, 410);
            send("POST", "/api/auth/social/signup",
                    Map.of("registrationToken", pendingDuringWithdrawal, "nickname", fixture + "Re", "agreements", consents),
                    null, 410);
            send("POST", "/api/auth/social/google", Map.of("idToken", "withdraw-identity"), null, 202);
            assertThat(db.queryForObject("SELECT account_status FROM users WHERE user_id=?", String.class, member.id))
                    .isEqualTo("WITHDRAWN");
            assertThat(db.queryForObject("SELECT nickname FROM users WHERE user_id=?", String.class, member.id))
                    .doesNotContain(fixture);
            for (String table : List.of("user_preferences", "user_favorites", "user_social_accounts",
                    "user_email_credentials", "user_refresh_sessions", "user_consents", "auth_action_tickets")) {
                assertThat(db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Long.class, member.id))
                        .as(table).isZero();
            }
        } finally {
            db.update("DELETE FROM user_favorites WHERE user_id=?", member.id);
            db.update("DELETE FROM charging_stations WHERE station_id=?", stationId);
            db.update("DELETE FROM users WHERE user_id=?", member.id);
        }
    }

    @Test
    void refusesProviderProofThatStartedBeforeDisconnectEvenWhenItFinishesLater() throws Exception {
        var member = account("fenced");
        var other = account("recipient");
        String access = member.tokens.accessToken();
        String subject = fixture + "fence";
        var provider = application.getBean("stubVerifier", SocialIdentityVerifier.class);
        var identity = new SocialIdentityVerifier.Identity("KAKAO", subject, address("fenced"), null, null);
        when(provider.verify("KAKAO", "setup-fence")).thenReturn(identity);
        send("POST", "/api/users/me/social-accounts/kakao",
                Map.of("providerAccessToken", "setup-fence", "reauthenticationToken", reauth(access, "LINK_SOCIAL")),
                access, 201);
        String unlink = reauth(access, "UNLINK_SOCIAL");
        var verified = new CountDownLatch(2);
        var finish = new CountDownLatch(1);
        when(provider.verify("KAKAO", "before-disconnect")).thenAnswer(call -> {
            verified.countDown();
            if (!finish.await(6, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Disconnect did not finish");
            }
            return identity;
        });
        String recipient = other.tokens.accessToken();
        String link = reauth(recipient, "LINK_SOCIAL");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var login = executor.submit(() -> send("POST", "/api/auth/social/kakao",
                    Map.of("providerAccessToken", "before-disconnect"), null, 401));
            var linking = executor.submit(() -> send("POST", "/api/users/me/social-accounts/kakao",
                    Map.of("providerAccessToken", "before-disconnect", "reauthenticationToken", link), recipient, 401));
            try {
                assertThat(verified.await(4, TimeUnit.SECONDS)).isTrue();
                send("DELETE", "/api/users/me/social-accounts/kakao", Map.of("reauthenticationToken", unlink), access, 204);
            } finally {
                finish.countDown();
            }
            assertCode(login.get(), "INVALID_PROVIDER_TOKEN");
            assertCode(linking.get(), "INVALID_PROVIDER_TOKEN");
        }
        assertThat(send("GET", "/api/users/me/social-accounts", null, recipient, 200).path("items").size()).isZero();
        send("POST", "/api/auth/social/kakao", Map.of("providerAccessToken", "setup-fence"), null, 202);
    }

    @Test
    void deniesOldProviderProofAndAllowsRetryAfterLocalRollbackFollowingRemoteUnlink() throws Exception {
        var member = account("rollback");
        String access = member.tokens.accessToken();
        String subject = fixture + "rollback";
        var identity = new SocialIdentityVerifier.Identity("KAKAO", subject, address("rollback"), null, null);
        var verifier = application.getBean("stubVerifier", SocialIdentityVerifier.class);
        when(verifier.verify("KAKAO", "rollback-provider")).thenReturn(identity);
        send("POST", "/api/users/me/social-accounts/kakao",
                Map.of("providerAccessToken", "rollback-provider", "reauthenticationToken", reauth(access, "LINK_SOCIAL")),
                access, 201);
        String unlink = reauth(access, "UNLINK_SOCIAL");
        var started = application.getBean(SocialProofGuard.class).now();
        var jwt = application.getBean(WattUpJwt.class).decode(access);
        var actions = application.getBean(AccountActions.class);
        new TransactionTemplate(application.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx -> {
            actions.unlink(jwt, "KAKAO", unlink);
            tx.setRollbackOnly();
        });
        assertThat(send("GET", "/api/users/me/social-accounts", null, access, 200).path("items").size()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> application.getBean(AuthAccounts.class).social(identity, started))
                .isInstanceOfSatisfying(AuthFailure.class, failure -> assertThat(failure.code).isEqualTo("INVALID_PROVIDER_TOKEN"));
        send("DELETE", "/api/users/me/social-accounts/kakao", Map.of("reauthenticationToken", unlink), access, 204);
    }

    @Test
    void favoritesAreAccountScopedAndImportIsIdempotent() throws Exception {
        var first = account("favorites");
        var second = account("otherfav");
        String access = first.tokens.accessToken();
        String visible = "KECO:" + fixture + "V";
        String added = "KECO:" + fixture + "A";
        for (String station : List.of(visible, added)) {
            db.update("INSERT INTO charging_stations (station_id,source_station_id,station_name) VALUES (?,?,'Test station')",
                    station, station.substring(5));
        }
        try {
            String base = "/api/users/me/favorites";
            send("GET", base, null, null, 401);
            send("POST", base, Map.of("stationId", visible), access, 201);
            assertCode(send("POST", base, Map.of("stationId", visible), access, 409), "FAVORITE_ALREADY_EXISTS");
            assertThat(send("GET", base, null, second.tokens.accessToken(), 200).path("total").asInt()).isZero();
            send("DELETE", base + "/" + visible, null, second.tokens.accessToken(), 204);

            var body = Map.of("stationIds", List.of(visible, added, added, "invalid", "KECO:NOT_FOUND"), "mergeMode", "MERGE");
            var imported = send("POST", base + "/import", body, access, 200);
            assertThat(imported.path("imported").asInt()).isEqualTo(1);
            assertThat(imported.path("alreadyExisted").asInt()).isEqualTo(2);
            assertThat(imported.path("invalid").asInt()).isEqualTo(2);
            assertThat(send("POST", base + "/import", body, access, 200).path("imported").asInt()).isZero();
            var page = send("GET", base + "?page=0&size=1", null, access, 200);
            assertThat(page.path("total").asInt()).isEqualTo(2);
            assertThat(page.path("hasNext").asBoolean()).isTrue();
            assertThat(page.path("items").get(0).path("current").path("freshness").asText()).isEqualTo("UNKNOWN");
            assertThat(send("GET", base + "?page=9&size=1", null, access, 200).path("total").asInt()).isEqualTo(2);
            db.update("UPDATE charging_stations SET source_limited=TRUE WHERE station_id=?", visible);
            assertCode(send("POST", base, Map.of("stationId", visible), second.tokens.accessToken(), 404), "STATION_NOT_FOUND");
            assertThat(send("POST", base + "/import", Map.of("stationIds", List.of(visible), "mergeMode", "MERGE"),
                    access, 200).path("alreadyExisted").asInt()).isEqualTo(1);
            assertThat(send("POST", base + "/import", Map.of("stationIds", java.util.Arrays.asList(visible, null),
                    "mergeMode", "MERGE"), access, 200).path("invalid").asInt()).isEqualTo(1);
            assertThat(send("POST", base + "/import", Map.of("stationIds", List.of(visible), "mergeMode", "MERGE"),
                    second.tokens.accessToken(), 200).path("invalid").asInt()).isEqualTo(1);
            var hidden = send("GET", base, null, access, 200).path("items").valueStream()
                    .filter(item -> visible.equals(item.path("stationId").asText())).findFirst().orElseThrow();
            assertThat(hidden.path("status").asText()).isEqualTo("UNAVAILABLE");
            assertThat(hidden.path("name").isNull()).isTrue();
            assertThat(hidden.path("current").isNull()).isTrue();
            send("DELETE", base + "/" + visible, null, access, 204);
            send("DELETE", base + "/" + visible, null, access, 204);
            assertThat(send("GET", base, null, access, 200).path("total").asInt()).isEqualTo(1);
        } finally {
            db.update("DELETE FROM user_favorites WHERE user_id IN (?,?)", first.id, second.id);
            db.update("DELETE FROM charging_stations WHERE station_id IN (?,?)", visible, added);
        }
    }

    @Test
    void preferencesPatchOnlyChangesSuppliedFieldsAndProtectsOtherAccounts() throws Exception {
        var first = account("preferences");
        var second = account("otherpref");
        String access = first.tokens.accessToken();
        String path = "/api/users/me/preferences";
        var defaults = send("GET", path, null, access, 200);
        assertThat(defaults.path("defaultRadiusM").asInt()).isEqualTo(3000);
        assertThat(defaults.path("defaultProfile").asText()).isEqualTo("ALL");
        var updated = send("PATCH", path, Map.of("defaultRadiusM", 5500, "defaultProfile", "DC_COMBO",
                "minOutputKw", 50, "defaultChargerTypeCodes", List.of("04", "06")), access, 200);
        assertThat(updated.path("minOutputKw").asDouble()).isEqualTo(50);
        assertThat(updated.path("defaultChargerTypeCodes").size()).isEqualTo(2);
        assertCode(send("PATCH", path, Map.of("defaultProfile", "AC_SLOW"), access, 422), "UNSUPPORTED_CHARGER_FILTER");
        assertCode(send("PATCH", path, Map.of("defaultRadiusM", 99), access, 400), "INVALID_RADIUS");
        assertCode(send("PATCH", path, Map.of("defaultProfile", "unknown"), access, 422), "UNSUPPORTED_PROFILE");
        assertCode(send("PATCH", path, Map.of("minOutputKw", 0.001), access, 400), "INVALID_REQUEST");
        assertCode(send("PATCH", path, Map.of("userId", second.id), access, 400), "INVALID_REQUEST");
        var clearing = new java.util.HashMap<String, Object>();
        clearing.put("minOutputKw", null);
        clearing.put("defaultChargerTypeCodes", null);
        var cleared = send("PATCH", path, clearing, access, 200);
        assertThat(cleared.path("minOutputKw").isNull()).isTrue();
        assertThat(cleared.path("defaultChargerTypeCodes").size()).isZero();
        assertThat(cleared.path("defaultRadiusM").asInt()).isEqualTo(5500);
        assertThat(cleared.path("defaultProfile").asText()).isEqualTo("DC_COMBO");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var radius = executor.submit(() -> send("PATCH", path, Map.of("defaultRadiusM", 6200), access, 200));
            var output = executor.submit(() -> send("PATCH", path, Map.of("minOutputKw", 80), access, 200));
            radius.get();
            output.get();
        }
        var combined = send("GET", path, null, access, 200);
        assertThat(combined.path("defaultRadiusM").asInt()).isEqualTo(6200);
        assertThat(combined.path("minOutputKw").asInt()).isEqualTo(80);
        var separate = send("GET", path, null, second.tokens.accessToken(), 200);
        assertThat(separate.path("defaultRadiusM").asInt()).isEqualTo(3000);
        assertThat(separate.path("defaultProfile").asText()).isEqualTo("ALL");
    }

    private Member account(String tag) {
        String nickname = fixture + tag;
        db.update("INSERT INTO users (nickname) VALUES (?)", nickname);
        long id = db.queryForObject("SELECT user_id FROM users WHERE nickname=?", Long.class, nickname);
        addPassword(id, address(tag));
        return new Member(id, application.getBean(AuthSessions.class).start(id, null));
    }

    private void addPassword(long id, String email) {
        var bcrypt = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4);
        db.update("""
                INSERT INTO user_email_credentials (user_id,email,password_hash,email_verified_at)
                VALUES (?,?,?,UTC_TIMESTAMP(3))
                """, id, email, bcrypt.encode(PASSWORD));
    }

    private String sessionId(AuthSessions.Tokens tokens) {
        return application.getBean(WattUpJwt.class).decode(tokens.accessToken()).getClaimAsString("sid");
    }

    private String reauth(String access, String purpose) throws Exception {
        return send("POST", "/api/users/me/reauthentication", Map.of("purpose", purpose,
                "method", "PASSWORD", "password", PASSWORD), access, 200).path("reauthenticationToken").asText();
    }

    private record Member(long id, AuthSessions.Tokens tokens) {}

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

        @Bean
        @Primary
        KakaoDisconnect stubDisconnect() {
            return mock(KakaoDisconnect.class);
        }
    }
}
