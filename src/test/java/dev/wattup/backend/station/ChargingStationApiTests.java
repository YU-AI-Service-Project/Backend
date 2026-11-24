package dev.wattup.backend.station;

import dev.wattup.backend.WattUpApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "WATTUP_TEST_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChargingStationApiTests {
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = new JsonMapper();
    private final String fixtureId = "Q" + UUID.randomUUID().toString().substring(0, 8);
    private final String near = "KECO:" + fixtureId + "N";
    private final String far = "KECO:" + fixtureId + "F";
    private final String hidden = "KECO:" + fixtureId + "H";
    private final String unknown = "KECO:" + fixtureId + "U";
    private ConfigurableApplicationContext application;
    private JdbcTemplate jdbc;
    private int port;
    private long runId;
    private LocalDateTime freshAt;
    private LocalDateTime staleAt;

    @BeforeAll
    void startApiWithTestInventory() {
        String testUrl = System.getenv("WATTUP_TEST_DB_URL");
        if (!testUrl.matches("jdbc:mysql://[^/?#]+/wattup_test(?:\\?[^#]*)?")) {
            throw new IllegalArgumentException("WATTUP_TEST_DB_URL must point to wattup_test");
        }
        application = new SpringApplicationBuilder(WattUpApplication.class).run("--spring.datasource.url=" + testUrl,
                "--server.port=0", "--server.address=127.0.0.1", "--wattup.collection.enabled=false");
        jdbc = application.getBean(JdbcTemplate.class);
        port = application.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        freshAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1).truncatedTo(ChronoUnit.SECONDS);
        staleAt = freshAt.minusMinutes(20);
        runId = insert("""
                INSERT INTO collection_runs (run_type, status, started_at, completed_at, is_complete)
                VALUES ('STATUS_FULL', 'SUCCEEDED', ?, ?, TRUE)
                """, staleAt, freshAt);
        station(near, 34.485678, 128.345678, false);
        station(far, 34.488678, 128.345678, false);
        station(hidden, 34.485679, 128.345678, true);
        station(unknown, 34.49, 128.345678, false);
        charger(near, "01", "04", 100, false, "AVAILABLE", "2", freshAt);
        charger(near, "02", "02", 7, false, "CHARGING", "3", staleAt);
        charger(near, "03", "04", 200, true, "AVAILABLE", "2", freshAt);
        charger(far, "01", "04", 75, false, "AVAILABLE", "2", staleAt);
        charger(hidden, "01", "04", 100, false, "AVAILABLE", "2", freshAt);
        charger(unknown, "01", "02", 7, false, null, null, null);
    }

    @AfterAll
    void removeOnlyTestInventory() {
        try {
            if (jdbc != null) {
                jdbc.update("DELETE FROM charger_current_status WHERE latest_observation_id IN "
                        + "(SELECT observation_id FROM charger_status_observations WHERE collection_run_id = ?)", runId);
                jdbc.update("DELETE FROM charger_status_observations WHERE collection_run_id = ?", runId);
                jdbc.update("DELETE FROM chargers WHERE station_id IN (?, ?, ?, ?)", near, far, hidden, unknown);
                jdbc.update("DELETE FROM charging_stations WHERE station_id IN (?, ?, ?, ?)", near, far, hidden, unknown);
                jdbc.update("DELETE FROM collection_runs WHERE run_id = ?", runId);
            }
        } finally {
            if (application != null) {
                application.close();
            }
        }
    }

    @Test
    void searchesWithoutLoginAndCountsAllMatchingStationsBeforeApplyingTheLimit() throws Exception {
        var result = get("?latitude=34.485678&longitude=128.345678&radiusM=500&profile=DC_COMBO"
                + "&chargerTypes=02&chargerTypes=04&minOutputKw=70&limit=1");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().path("total").asInt()).isEqualTo(2);
        var item = result.body().path("items").get(0);
        assertThat(result.body().path("items").size()).isEqualTo(1);
        assertThat(item.path("stationId").asString()).isEqualTo(near);
        assertThat(item.path("straightDistanceM").asInt()).isZero();
        assertThat(item.path("current").path("availableCount").asInt()).isEqualTo(1);
        assertThat(item.path("freshness").asString()).isEqualTo("FRESH");
    }

    @Test
    void availableOnlyIgnoresBothStaleAndRestrictedChargers() throws Exception {
        var result = get("?latitude=34.485678&longitude=128.345678&radiusM=500&availableOnly=true");
        assertThat(result.body().path("total").asInt()).isEqualTo(1);
        assertThat(result.body().path("items").get(0).path("stationId").asString()).isEqualTo(near);
    }

    @Test
    void returnsDetailsAndSeparatelyFilteredChargersWithoutInternalIds() throws Exception {
        var detail = get("/" + near);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().path("operator").path("name").asString()).isEqualTo("Operator");
        assertThat(detail.body().path("parkingInfo").isNull()).isTrue();
        assertThat(detail.body().path("chargers").size()).isEqualTo(2);
        assertThat(detail.body().path("current").path("totalCount").asInt()).isEqualTo(2);
        assertThat(detail.body().path("freshness").asString()).isEqualTo("STALE");

        var list = get("/" + near + "/chargers?typeCode=02&minOutputKw=7");
        var charger = list.body().path("items").get(0);
        assertThat(list.body().path("total").asInt()).isEqualTo(1);
        assertThat(charger.path("chargerId").asString()).isEqualTo("02");
        assertThat(charger.path("status").asString()).isEqualTo("CHARGING");
        assertThat(charger.has("chargerPk")).isFalse();
    }

    @Test
    void distinguishesStaleStatusFromAChargerThatWasNeverObserved() throws Exception {
        var stale = get("/" + far + "/status");
        assertThat(stale.body().path("freshness").asString()).isEqualTo("STALE");
        assertThat(stale.body().path("asOf").asString()).isEqualTo(staleAt.atOffset(ZoneOffset.UTC).toString());
        assertThat(stale.body().path("warning").asString()).isNotBlank();

        var unobserved = get("/" + unknown + "/status");
        assertThat(unobserved.body().path("freshness").asString()).isEqualTo("UNKNOWN");
        assertThat(unobserved.body().path("asOf").isNull()).isTrue();
        assertThat(unobserved.body().path("current").path("unknownCount").asInt()).isEqualTo(1);
    }

    @Test
    void returnsEmptyResultsAndStructuredErrors() throws Exception {
        assertThat(get("?latitude=0&longitude=0&radiusM=100").body().path("total").asInt()).isZero();
        assertError("?latitude=NaN&longitude=0", 400, "INVALID_COORDINATES");
        assertError("?latitude=35&longitude=128&radiusM=25", 400, "INVALID_RADIUS");
        assertError("?longitude=128", 400, "INVALID_REQUEST");
        assertError("?latitude=35&longitude=128&limit=0", 400, "INVALID_REQUEST");
        assertError("?latitude=35&longitude=128&minOutputKw=1000000", 400, "INVALID_REQUEST");
        assertError("?latitude=35&longitude=128&chargerTypes=bad", 422, "UNSUPPORTED_CHARGER_FILTER");
        assertError("?latitude=35&longitude=128&profile=UNSUPPORTED", 422, "UNSUPPORTED_CHARGER_FILTER");
        assertError("/" + near + "/status?profile=UNSUPPORTED", 400, "INVALID_REQUEST");
        assertError("/" + near + "/chargers?typeCode=bad", 400, "INVALID_REQUEST");
        assertError("/" + hidden, 404, "STATION_NOT_FOUND");
    }

    @Test
    void recommendsCurrentStationsWhenThePredictionCohortDiffersOrHistoryIsMissing() throws Exception {
        var result = getPath("/api/recommendations?latitude=34.485678&longitude=128.345678&radiusM=500"
                + "&profile=ALL&minOutputKw=70");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().path("totalCandidates").asInt()).isEqualTo(2);
        assertThat(result.body().path("rankingBasis").asString()).isEqualTo("DISTANCE_CURRENT_STATUS_OUTPUT");
        var items = result.body().path("items");
        assertThat(items.size()).isEqualTo(2);
        var first = items.path(0);
        assertThat(first.path("stationId").asString()).isEqualTo(near);
        assertThat(first.path("rank").asInt()).isEqualTo(1);
        assertThat(first.path("outputKw").asDouble()).isEqualTo(100);
        assertThat(first.path("current").path("freshAvailableCount").asInt()).isEqualTo(1);
        assertThat(first.path("prediction").path("status").asString()).isEqualTo("OUTPUT_FILTER_NOT_SUPPORTED");
        assertThat(first.path("prediction").path("probability").isNull()).isTrue();
        assertThat(first.path("roadDistanceM").isNull()).isTrue();
        assertThat(items.path(1).path("stationId").asString()).isEqualTo(far);
        assertThat(items.path(1).path("current").path("freshAvailableCount").asInt()).isZero();
        assertThat(items.path(1).path("prediction").path("status").asString()).isEqualTo("INPUT_INCOMPLETE");
    }

    @Test
    void recommendsOnlyExistingCandidatesAndValidatesQuery() throws Exception {
        assertThat(getPath("/api/recommendations?latitude=0&longitude=0&profile=ALL").body()
                .path("items").size()).isZero();
        var one = getPath("/api/recommendations?latitude=34.485678&longitude=128.345678&radiusM=100&profile=DC_COMBO");
        assertThat(one.body().path("items").size()).isEqualTo(1);
        var invalid = getPath("/api/recommendations?latitude=35&longitude=128&profile=ALL&limit=4");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body().path("error").path("code").asString()).isEqualTo("INVALID_REQUEST");
    }

    private void assertError(String suffix, int status, String code) throws Exception {
        var response = get(suffix);
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body().path("error").path("code").asString()).isEqualTo(code);
        assertThat(response.body().path("requestId").asString()).isNotBlank();
    }

    private Response get(String suffix) throws Exception {
        return getPath("/api/charging-stations" + suffix);
    }

    private Response getPath(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
        var result = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(result.statusCode(), json.readTree(result.body()));
    }

    private void station(String id, double latitude, double longitude, boolean restricted) {
        jdbc.update("""
                INSERT INTO charging_stations (station_id, source_station_id, station_name, latitude, longitude,
                  operator_name, source_limited)
                VALUES (?, ?, 'Fixture station', ?, ?, 'Operator', ?)
                """, id, id.substring(5), latitude, longitude, restricted);
    }

    private void charger(String station, String id, String type, int output, boolean restricted,
                         String status, String raw, LocalDateTime observedAt) {
        long charger = insert("""
                INSERT INTO chargers (station_id, source_charger_id, charger_type_code, output_kw, source_limited)
                VALUES (?, ?, ?, ?, ?)
                """, station, id, type, output, restricted);
        if (observedAt == null) {
            return;
        }
        long observation = insert("""
                INSERT INTO charger_status_observations (charger_id, collection_run_id, raw_status_code, normalized_status,
                  observed_at, source_event_fingerprint)
                VALUES (?, ?, ?, ?, ?, REPEAT('a',64))
                """, charger, runId, raw, status, observedAt);
        jdbc.update("""
                INSERT INTO charger_current_status (charger_id, latest_observation_id, raw_status_code, normalized_status,
                  last_observed_at)
                VALUES (?, ?, ?, ?, ?)
                """, charger, observation, raw, status, observedAt);
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private record Response(int status, JsonNode body) {
    }
}
