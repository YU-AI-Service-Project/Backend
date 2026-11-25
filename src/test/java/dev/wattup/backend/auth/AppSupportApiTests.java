package dev.wattup.backend.auth;

import dev.wattup.backend.WattUpApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "WATTUP_TEST_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppSupportApiTests {
    private static final String NOTICES = "/api/app/notices";
    private static final String REPORTS = "/api/support/reports";
    private static final String PASSWORD = "SyntheticPassphrase-1";
    private static final Map<String, String> REPORT = Map.of("category", "APP_ERROR", "message", "Screen failed");
    private final JsonMapper json = new JsonMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String fixture = "Support" + UUID.randomUUID().toString().substring(0, 8);
    private final List<Long> users = new ArrayList<>();
    private ConfigurableApplicationContext application;
    private JdbcTemplate db;
    private int port;
    private boolean noticesInserted;

    @BeforeAll
    void launch() {
        String url = System.getenv("WATTUP_TEST_DB_URL");
        if (!url.matches("jdbc:mysql://[^/?#]+/wattup_test(?:\\?[^#]*)?")) {
            throw new IllegalArgumentException("WATTUP_TEST_DB_URL must point to wattup_test");
        }
        String testKey = Base64.getEncoder().encodeToString(new byte[32]);
        application = new SpringApplicationBuilder(WattUpApplication.class).run(
                "--spring.datasource.url=" + url, "--wattup.collection.enabled=false",
                "--server.port=0", "--server.address=127.0.0.1", "--wattup.auth.jwt-secret=" + testKey,
                "--wattup.auth.email-secret=" + testKey, "--wattup.account.withdrawal-enabled=true");
        db = application.getBean(JdbcTemplate.class);
        port = application.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    @AfterAll
    void cleanup() {
        try {
            if (db != null) {
                if (noticesInserted) {
                    db.update("DELETE FROM app_notices WHERE title LIKE ?", fixture + "%");
                }
                for (long id : users) {
                    db.update("DELETE FROM users WHERE user_id=?", id);
                    var rate = application.getBean(AuthRateLimit.class);
                    db.update("DELETE FROM auth_request_buckets WHERE bucket_key IN (?,?)",
                            rate.key("support-report", Long.toString(id)), rate.key("reauth", Long.toString(id)));
                }
                db.update("DELETE FROM charging_stations WHERE source_station_id LIKE ?", fixture + "%");
            }
        } finally {
            if (application != null) {
                application.close();
            }
            http.close();
        }
    }

    @Test
    void anonymousNoticesIncludeOnlyPublishedActiveRowsInStablePages() throws Exception {
        long initial = send("GET", NOTICES, null, null, 200).path("total").asLong();
        noticesInserted = true;
        db.update("""
                INSERT INTO app_notices (title, content, severity, is_active, published_at) VALUES
                    (?, 'First', 'INFO', TRUE, UTC_TIMESTAMP(3)),
                    (?, 'Second', 'WARNING', TRUE, UTC_TIMESTAMP(3)),
                    (?, 'Scheduled', 'INFO', TRUE, UTC_TIMESTAMP(3) + INTERVAL 1 DAY),
                    (?, 'Inactive', 'INFO', FALSE, UTC_TIMESTAMP(3)),
                    (?, 'Unpublished', 'CRITICAL', TRUE, NULL)
                """, fixture + " first", fixture + " second", fixture + " future", fixture + " inactive",
                fixture + " unpublished");
        var page = send("GET", NOTICES + "?page=0&size=1", null, null, 200);
        var item = page.path("items").get(0);
        assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(item.path("noticeId").asText()).matches("[0-9]+");
        assertThat(item.path("title").asText()).isEqualTo(fixture + " second");
        assertThat(item.path("content").asText()).isEqualTo("Second");
        assertThat(item.path("severity").asText()).isEqualTo("WARNING");
        assertThat(item.path("publishedAt").asText()).endsWith("Z");
        assertThat(page.path("total").asLong()).isEqualTo(initial + 2);
        assertThat(send("GET", NOTICES + "?page=1&size=1", null, null, 200).path("items").get(0)
                .path("title").asText()).isEqualTo(fixture + " first");
        assertThat(send("GET", NOTICES + "?page=2147483647&size=100", null, null, 200).path("items").size()).isZero();
        send("GET", NOTICES + "?size=101", null, null, 400);
    }

    @Test
    void reportsRequireAnAuthenticatedAccount() throws Exception {
        assertThat(send("POST", REPORTS, REPORT, null, 401).path("error").path("code").asText())
                .isEqualTo("AUTHENTICATION_REQUIRED");
    }

    @Test
    void reportsRejectInvalidShapesAndUnavailableStations() throws Exception {
        var member = member("Validation");
        String station = fixture + "Hidden";
        db.update("""
                INSERT INTO charging_stations (station_id,source_station_id,station_name,source_limited)
                VALUES (?,?,'Hidden',TRUE)
                """, "KECO:" + station, station);
        for (var invalid : List.of(Map.of("category", "APP_ERROR", "message", 100),
                Map.of("category", "APP_ERROR", "message", "a".repeat(2_001)),
                Map.of("category", "APP_ERROR", "message", "ok", "email", "ignored@example.test"),
                Map.of("category", "APP_ERROR", "message", "ok", "contactEmail", "wrong"))) {
            send("POST", REPORTS, invalid, member.access, 400);
        }
        assertThat(send("POST", REPORTS,
                Map.of("category", "STATION_INFORMATION", "message", "Fix this", "stationId", "KECO:" + station),
                member.access, 404).path("error").path("code").asText()).isEqualTo("STATION_NOT_FOUND");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM support_reports WHERE user_id=?", Integer.class, member.id))
                .isZero();
    }

    @Test
    void reportsRejectAmbiguousAndOversizedJson() throws Exception {
        var member = member("Json");
        send("POST", REPORTS, """
                {"category":"APP_ERROR","message":"first","message":"second"}
                """, member.access, 400);
        send("POST", REPORTS, """
                {"category":"APP_ERROR","message":"first"} {}
                """, member.access, 400);
        send("POST", REPORTS, " ".repeat(16_384) + json.writeValueAsString(REPORT), member.access, 413);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM support_reports WHERE user_id=?", Integer.class, member.id))
                .isZero();
    }

    @Test
    void reportsStoreOnlySubmittedContactAndArePurgedOnWithdrawal() throws Exception {
        var member = member("Withdraw");
        var other = member("Other");
        db.update("""
                INSERT INTO user_email_credentials (user_id,email,password_hash,email_verified_at)
                VALUES (?,?,?,UTC_TIMESTAMP(3))
                """, member.id, fixture.toLowerCase(Locale.ROOT) + "@example.test",
                new BCryptPasswordEncoder(4).encode(PASSWORD));
        String station = fixture + "Public";
        db.update("INSERT INTO charging_stations (station_id,source_station_id,station_name) VALUES (?,?,'Public')",
                "KECO:" + station, station);
        var received = send("POST", REPORTS, REPORT, member.access, 201);
        assertThat(received.path("reportId").asText()).matches("[0-9]+");
        assertThat(received.path("status").asText()).isEqualTo("RECEIVED");
        assertThat(db.queryForObject("SELECT contact_email FROM support_reports WHERE report_id=? AND user_id=?",
                String.class, received.path("reportId").asLong(), member.id)).isNull();
        var explicit = send("POST", REPORTS, Map.of("category", "STATION_INFORMATION", "stationId", "KECO:" + station,
                "message", "Hours are wrong", "contactEmail", "contact@example.test"), member.access, 201);
        assertThat(db.queryForMap("SELECT station_id, category, message, contact_email FROM support_reports WHERE report_id=?",
                explicit.path("reportId").asLong())).containsExactlyInAnyOrderEntriesOf(Map.of("station_id", "KECO:" + station,
                "category", "STATION_INFORMATION", "message", "Hours are wrong", "contact_email", "contact@example.test"));
        send("POST", REPORTS, REPORT, other.access, 201);
        String ticket = send("POST", "/api/users/me/reauthentication", Map.of("purpose", "WITHDRAW", "method", "PASSWORD",
                "password", PASSWORD), member.access, 200).path("reauthenticationToken").asText();
        send("DELETE", "/api/users/me", Map.of("confirmation", "WITHDRAW", "reauthenticationToken", ticket), member.access, 204);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM support_reports WHERE user_id=?", Integer.class, member.id)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM support_reports WHERE user_id=?", Integer.class, other.id)).isOne();
    }

    @Test
    void reportsShareASubmissionLimitAcrossDevicesOnTheSameAccount() throws Exception {
        var member = member("Limit");
        for (int i = 0; i < 5; i++) {
            send("POST", REPORTS, REPORT, member.access, 201);
        }
        String device = application.getBean(AuthSessions.class).start(member.id, "Second device").accessToken();
        assertThat(send("POST", REPORTS, REPORT, device, 429).path("error").path("code").asText()).isEqualTo("RATE_LIMITED");
        send("POST", REPORTS, REPORT, member("Separate").access, 201);
    }

    private Member member(String suffix) {
        String nickname = fixture + suffix;
        db.update("INSERT INTO users (nickname) VALUES (?)", nickname);
        long id = db.queryForObject("SELECT user_id FROM users WHERE nickname=?", Long.class, nickname);
        users.add(id);
        return new Member(id, application.getBean(AuthSessions.class).start(id, "Test device").accessToken());
    }

    private JsonNode send(String method, String path, Object body, String access, int status) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
        if (access != null) {
            request.header("Authorization", "Bearer " + access);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body instanceof String raw ? raw : json.writeValueAsString(body));
        var response = http.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(status);
        return response.body().isEmpty() ? json.nullNode() : json.readTree(response.body());
    }

    private record Member(long id, String access) {}
}
