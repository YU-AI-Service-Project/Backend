package dev.wattup.backend.prediction;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.wattup.backend.WattUpApplication;
import dev.wattup.backend.station.ChargerProfile;
import dev.wattup.backend.station.PredictionQualityCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "WATTUP_TEST_DB_URL", matches = ".+")
class EtaHistoryTests {
    private static final LocalDateTime GRID_END = LocalDateTime.parse("2026-11-12T01:00:00");
    private static final Instant AS_OF = GRID_END.toInstant(ZoneOffset.UTC);

    @Test
    void servesCurrentStatusAndValidatedEtaTogetherAndKeepsCurrentStatusOnAiFailure() throws Exception {
        String url = System.getenv("WATTUP_TEST_DB_URL");
        if (!url.matches("jdbc:mysql://[^/?#]+/wattup_test(?:\\?[^#]*)?")) {
            throw new IllegalArgumentException("WATTUP_TEST_DB_URL must point to wattup_test");
        }
        String station = "KECO:FLOW" + UUID.randomUUID().toString().substring(0, 8);
        String region = "XF";
        Instant now = Instant.now();
        Instant grid = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), 300) * 300);
        var localGrid = LocalDateTime.ofInstant(grid, ZoneOffset.UTC);
        var json = new JsonMapper();
        var invalidForecast = new AtomicBoolean();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/route", exchange -> respond(exchange, json, Map.of("routes", List.of(
                Map.of("result_code", 0, "summary", Map.of("duration", 600, "distance", 1800))))));
        upstream.createContext("/arrival", exchange -> {
            var batch = json.readTree(exchange.getRequestBody());
            var item = batch.path("items").get(0);
            if (batch.path("items").size() != 1 || !station.equals(item.path("stationId").asText())
                    || !"daegu-eta-v4".equals(batch.path("featureVersion").asText())
                    || item.path("features").path("availableLag30").asInt() != 1) {
                respond(exchange, json, Map.of("invalidRequest", true));
                return;
            }
            var forecast = grid.plusSeconds(invalidForecast.get() ? 900 : 600);
            respond(exchange, json, Map.of("requestId", batch.path("requestId"), "items", List.of(Map.ofEntries(
                    Map.entry("requestItemId", item.path("requestItemId")), Map.entry("stationId", station),
                    Map.entry("profile", "AC_SLOW"), Map.entry("status", "OK"), Map.entry("probability", 0.82),
                    Map.entry("modelVersion", "fixture-model"), Map.entry("featureVersion", "daegu-eta-v4"),
                    Map.entry("etaSeconds", 600), Map.entry("roadDistanceM", 1800), Map.entry("horizonMinutes", 10),
                    Map.entry("asOf", grid.toString()), Map.entry("requestAt", grid.toString()),
                    Map.entry("requestedArrivalAt", grid.plusSeconds(600).toString()),
                    Map.entry("forecastAt", forecast.toString()), Map.entry("featureAgeSeconds", 0),
                    Map.entry("forecastRoundingSeconds", 0)))));
        });
        upstream.start();
        String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
        try (var app = new SpringApplicationBuilder(WattUpApplication.class, RouteFixture.class).run(
                "--spring.datasource.url=" + url, "--wattup.collection.enabled=false", "--server.port=0",
                "--server.address=127.0.0.1", "--wattup.prediction.ai-batch-url=" + base + "/arrival",
                "--wattup.prediction.ai-feature-version=daegu-eta-v4",
                "--wattup.prediction.ai-bearer-token=fixture", "--wattup.prediction.kakao-rest-api-key=",
                "--wattup.prediction.quality-capture-enabled=true",
                "--test.route-url=" + base + "/route", "--test.prediction-time=" + grid);
             var http = HttpClient.newHttpClient()) {
            var jdbc = app.getBean(JdbcTemplate.class);
            var runs = new ArrayList<Long>();
            try {
                new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx -> {
                    jdbc.update("INSERT INTO charging_stations (station_id,source_station_id,station_name,latitude,longitude)"
                            + " VALUES (?,?,'Flow fixture',33.99117,127.22481)", station, station.substring(5));
                    jdbc.update("INSERT INTO chargers (station_id,source_charger_id,charger_type_code,output_kw,inventory_region)"
                            + " VALUES (?,'01','02',7,?)", station, region);
                    long charger = jdbc.queryForObject("SELECT charger_id FROM chargers WHERE station_id=?", Long.class, station);
                    long full = run(jdbc, "STATUS_FULL", region, localGrid.minusMinutes(30), 0, true);
                    runs.add(full);
                    observe(jdbc, charger, full, "2", "AVAILABLE", localGrid.minusMinutes(30));
                    for (int sample = 0; sample < 7; sample++) {
                        runs.add(run(jdbc, "STATUS_DELTA", region, localGrid.minusMinutes(30 - sample * 5), 0, true));
                    }
                    jdbc.update("""
                            INSERT INTO charger_current_status (charger_id,latest_observation_id,raw_status_code,
                              normalized_status,last_observed_at)
                            SELECT charger_id,observation_id,'2','AVAILABLE',? FROM charger_status_observations
                            WHERE charger_id=? AND collection_run_id=?
                            """, localGrid, charger, full);
                });
                int port = app.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/recommendations?latitude=33.99117&longitude=127.22481&radiusM=100&profile=AC_SLOW"))
                        .timeout(Duration.ofSeconds(10)).GET().build();
                for (boolean malformed : new boolean[]{false, true}) {
                    invalidForecast.set(malformed);
                    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(200);
                    var result = json.readTree(response.body());
                    var item = result.path("items").get(0);
                    assertThat(item.path("stationId").asText()).isEqualTo(station);
                    assertThat(item.path("current").path("freshAvailableCount").asInt()).isEqualTo(1);
                    assertThat(item.path("etaSeconds").asInt()).isEqualTo(600);
                    assertThat(item.path("prediction").path("status").asText())
                            .isEqualTo(malformed ? "MODEL_UNAVAILABLE" : "OK");
                    if (malformed) {
                        assertThat(item.path("prediction").path("probability").isNull()).isTrue();
                        assertThat(result.path("rankingBasis").asText()).isEqualTo("DISTANCE_CURRENT_STATUS_OUTPUT");
                    } else {
                        assertThat(item.path("prediction").path("probability").asDouble()).isEqualTo(0.82);
                        assertThat(result.path("rankingBasis").asText()).isEqualTo("DISTANCE_CURRENT_STATUS_OUTPUT_PREDICTION");
                    }
                }
                String countSql = "SELECT COUNT(*) FROM eta_prediction_samples WHERE station_id=?";
                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (jdbc.queryForObject(countSql, Long.class, station) == 0 && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                assertThat(jdbc.queryForObject(countSql, Long.class, station)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT probability FROM eta_prediction_samples WHERE station_id=?",
                        Double.class, station)).isEqualTo(0.82);
                var unverified = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/charging-stations/" + station + "/prediction-quality?profile=AC_SLOW")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(unverified.statusCode()).isEqualTo(200);
                assertThat(json.readTree(unverified.body()).path("dataSufficiency").asText()).isEqualTo("UNVERIFIED_MODEL");
            } finally {
                jdbc.update("DELETE cs FROM charger_current_status cs JOIN chargers c USING (charger_id) WHERE c.station_id=?", station);
                jdbc.update("DELETE o FROM charger_status_observations o JOIN chargers c USING (charger_id) WHERE c.station_id=?", station);
                jdbc.update("DELETE FROM chargers WHERE station_id=?", station);
                jdbc.update("DELETE FROM charging_stations WHERE station_id=?", station);
                runs.forEach(run -> jdbc.update("DELETE FROM collection_runs WHERE run_id=?", run));
            }
        } finally {
            upstream.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, JsonMapper json, Object body) throws IOException {
        try (exchange) {
            byte[] payload = json.writeValueAsBytes(body);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RouteFixture {
        @Bean
        @Primary
        PredictionService loopbackPrediction(EtaHistoryRepository history, EtaBatchClient ai, JsonMapper json,
                ObjectProvider<PredictionQualityCapture> quality,
                @Value("${test.route-url}") URI route, @Value("${test.prediction-time}") String time) {
            var clock = Clock.fixed(Instant.parse(time), ZoneOffset.UTC);
            var routes = new KakaoRouteClient(HttpClient.newHttpClient(), json, route, "fixture", Duration.ofSeconds(2), clock);
            return new PredictionService(history, routes, ai, clock, Duration.ofSeconds(9), quality.getIfAvailable());
        }
    }

    @Test
    void readsOnlyCompleteCausalHistoryForTheStationRegion() {
        String url = System.getenv("WATTUP_TEST_DB_URL");
        if (!url.matches("jdbc:mysql://[^/?#]+/wattup_test(?:\\?[^#]*)?")) {
            throw new IllegalArgumentException("WATTUP_TEST_DB_URL must point to wattup_test");
        }
        try (var app = new SpringApplicationBuilder(WattUpApplication.class).web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + url, "--wattup.collection.enabled=false")) {
            var jdbc = app.getBean(JdbcTemplate.class);
            var history = new EtaHistoryRepository(app.getBean(NamedParameterJdbcTemplate.class));
            new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx -> {
                tx.setRollbackOnly();
                String station = "KECO:ETA" + UUID.randomUUID().toString().substring(0, 8);
                jdbc.update("INSERT INTO charging_stations (station_id,source_station_id,station_name,latitude,longitude)"
                        + " VALUES (?,?,'ETA fixture',35.1,128.2)", station, station.substring(5));
                jdbc.update("INSERT INTO chargers (station_id,source_charger_id,charger_type_code,output_kw,inventory_region)"
                        + " VALUES (?,'01','02',7,'XY')", station);
                long charger = jdbc.queryForObject("SELECT charger_id FROM chargers WHERE station_id=?", Long.class, station);
                long full = run(jdbc, "STATUS_FULL", "XY", GRID_END.minusMinutes(30), 0, true);
                observe(jdbc, charger, full, "2", "AVAILABLE", GRID_END.minusMinutes(30));
                long initialObservation = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                for (int sample = 0; sample < 7; sample++) {
                    run(jdbc, "STATUS_DELTA", "XY", GRID_END.minusMinutes(30 - sample * 5), 0, true);
                }
                long future = run(jdbc, "STATUS_DELTA", "XY", GRID_END.minusSeconds(10), 20, true);
                observe(jdbc, charger, future, "9", "UNKNOWN", GRID_END.minusSeconds(10));
                run(jdbc, "STATUS_DELTA", "ZZ", GRID_END.minusMinutes(1), 0, false);
                var result = history.load(List.of(station), ChargerProfile.AC_SLOW, AS_OF).get(station);
                assertThat(result.latitude()).isEqualTo(35.1);
                assertThat(result.statusPollAt()).isEqualTo(AS_OF);
                assertThat(result.features()).containsEntry("availableCount", 1).containsEntry("availableLag30", 1)
                        .containsEntry("coverageLast30m", 1.0);

                var start = GRID_END.minusMinutes(9);
                long started = run(jdbc, "STATUS_DELTA", "XY", start, 0, true);
                observe(jdbc, charger, started, "3", "CHARGING", start);
                long chargingObservation = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                insertChange(jdbc, charger, initialObservation, chargingObservation, "2", "3", start);
                var end = GRID_END.minusMinutes(4);
                long ended = run(jdbc, "STATUS_DELTA", "XY", end, 0, true);
                observe(jdbc, charger, ended, "2", "AVAILABLE", end);
                long availableObservation = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbc.update("UPDATE charger_status_observations SET provider_status_updated_at=? WHERE observation_id=?",
                        end, availableObservation);
                insertChange(jdbc, charger, chargingObservation, availableObservation, "3", "2", end);
                var v9Features = history.load(List.of(station), ChargerProfile.AC_SLOW, AS_OF).get(station).features();
                assertThat(v9Features)
                        .containsEntry("availabilityLosses15", 1).containsEntry("availabilityGains15", 1)
                        .containsEntry("chargeStarts30", 1).containsEntry("chargeEnds30", 1)
                        .containsEntry("availableAgeMin", 4.0).containsEntry("availableAgeKnownFraction", 1.0);
                String liveAi = System.getenv("WATTUP_TEST_AI_V9_URL");
                if (liveAi != null && !liveAi.isBlank()) {
                    try (var http = HttpClient.newHttpClient()) {
                        var client = new EtaBatchClient(http, new JsonMapper(), URI.create(liveAi),
                                System.getenv("WATTUP_TEST_AI_V9_TOKEN"), Duration.ofSeconds(3),
                                EtaBatchClient.EVENT_FEATURE_VERSION);
                        var item = new EtaBatchClient.Item("fixture", station, ChargerProfile.AC_SLOW, AS_OF,
                                600, AS_OF, 1800, v9Features);
                        var live = client.predict("db-event-fixture", AS_OF, List.of(item)).orElseThrow().get("fixture");
                        assertThat(live.status()).isEqualTo("OK");
                        assertThat(live.probability()).isBetween(0., 1.);
                    }
                }

                jdbc.update("INSERT INTO chargers (station_id,source_charger_id,charger_type_code,output_kw,inventory_region)"
                        + " VALUES (?,'02','02',7,'XY')", station);
                long newCharger = jdbc.queryForObject("SELECT charger_id FROM chargers WHERE station_id=?"
                        + " AND source_charger_id='02'", Long.class, station);
                assertThat(history.load(List.of(station), ChargerProfile.AC_SLOW, AS_OF).get(station).features()).isEmpty();
                long latest = run(jdbc, "STATUS_DELTA", "XY", GRID_END, 0, true);
                observe(jdbc, newCharger, latest, "2", "AVAILABLE", GRID_END);
                assertThat(history.load(List.of(station), ChargerProfile.AC_SLOW, AS_OF).get(station).features())
                        .containsEntry("availableCount", 2).containsEntry("availableLag30", 1);

                long newerFull = run(jdbc, "STATUS_FULL", "XY", GRID_END.plusMinutes(1), 0, true);
                assertThat(history.load(List.of(station), ChargerProfile.AC_SLOW, AS_OF).get(station).features()).isEmpty();
                jdbc.update("DELETE FROM collection_runs WHERE run_id = ?", newerFull);

                run(jdbc, "STATUS_DELTA", "XY", GRID_END.minusMinutes(2), 0, false);
                result = history.load(List.of(station), ChargerProfile.AC_SLOW, AS_OF).get(station);
                assertThat(result.features()).isEmpty();
                assertThat(history.load(List.of(station), ChargerProfile.DC_COMBO, AS_OF).get(station).features()).isEmpty();
            });
        }
    }

    private static long run(JdbcTemplate jdbc, String type, String region, LocalDateTime start, int seconds,
                            boolean success) {
        jdbc.update("INSERT INTO collection_runs (run_type, status, region_code, started_at, completed_at, is_complete)"
                + " VALUES (?, ?, ?, ?, ?, ?)", type, success ? "SUCCEEDED" : "FAILED", region, start,
                start.plusSeconds(seconds), success);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static void observe(JdbcTemplate jdbc, long charger, long run, String raw, String normalized,
                                LocalDateTime observedAt) {
        jdbc.update("INSERT INTO charger_status_observations (charger_id, collection_run_id, raw_status_code,"
                + " normalized_status, observed_at, source_event_fingerprint) VALUES (?, ?, ?, ?, ?, ?)",
                charger, run, raw, normalized, observedAt, "a".repeat(64));
    }

    private static void insertChange(JdbcTemplate jdbc, long charger, long previous, long next,
                                     String previousStatus, String nextStatus, LocalDateTime visibleAt) {
        jdbc.update("""
                INSERT INTO charger_state_changes (charger_id, previous_observation_id, new_observation_id,
                   previous_status_code, new_status_code, change_observed_at) VALUES (?, ?, ?, ?, ?, ?)
                """, charger, previous, next, previousStatus, nextStatus, visibleAt);
    }
}
