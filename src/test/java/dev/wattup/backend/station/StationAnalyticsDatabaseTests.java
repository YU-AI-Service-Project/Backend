package dev.wattup.backend.station;

import dev.wattup.backend.WattUpApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@EnabledIfEnvironmentVariable(named = "WATTUP_TEST_DB_URL", matches = ".+")
class StationAnalyticsDatabaseTests {
    @Test
    void evaluatesOnlyDistinctForecastsWithCompleteMatchingOutcomes() {
        String url = System.getenv("WATTUP_TEST_DB_URL");
        if (!url.matches("jdbc:mysql://[^/?#]+/wattup_test(?:\\?[^#]*)?")) {
            throw new IllegalArgumentException("WATTUP_TEST_DB_URL must point to wattup_test");
        }
        try (var context = new SpringApplicationBuilder(WattUpApplication.class).web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + url, "--wattup.collection.enabled=false")) {
            var fixture = new Fixture(context.getBean(JdbcTemplate.class));
            var quality = context.getBean(PredictionQualityRepository.class);
            Instant start = Fixture.day(60);
            try {
                fixture.inventory();
                assertThat(quality.evaluate(fixture.station, ChargerProfile.ALL, start, Fixture.day(64)).sampleCount())
                        .isZero();
                fixture.sample(quality, start, "old", 0.9);
                for (int day = 0; day < 3; day++) {
                    for (int hour = 0; hour < 10; hour++) {
                        Instant target = start.plus(day, ChronoUnit.DAYS).plus(hour, ChronoUnit.HOURS);
                        fixture.sample(quality, target, "v4", hour < 5 ? 0.15 : 0.8);
                        fixture.full(target.plusSeconds(60), hour % 2 == 0 ? "AVAILABLE" : "CHARGING", "CHARGING");
                    }
                }
                fixture.sample(quality, start, "v4", 0.99);
                Instant unknown = Fixture.day(63);
                fixture.sample(quality, unknown, "v4", 0.1);
                fixture.full(unknown.plusSeconds(60), "UNKNOWN", "AVAILABLE");
                Instant tooLate = unknown.plus(1, ChronoUnit.HOURS);
                fixture.sample(quality, tooLate, "v4", 0.9);
                fixture.full(tooLate.plusSeconds(300), "AVAILABLE", "AVAILABLE");
                var result = quality.evaluate(fixture.station, ChargerProfile.ALL, start, Fixture.day(64));
                assertThat(result.modelVersion()).isEqualTo("v4");
                assertThat(result.sampleCount()).isEqualTo(30);
                assertThat(result.dayCount()).isEqualTo(3);
                assertThat(result.buckets()).containsExactly(
                        new PredictionQualityRepository.Bucket(0, 15, 0.6),
                        new PredictionQualityRepository.Bucket(4, 15, 0.4));
                fixture.charger("03", "04");
                assertThat(quality.evaluate(fixture.station, ChargerProfile.ALL, start, Fixture.day(64)).sampleCount())
                        .isZero();
            } finally {
                fixture.clean();
            }
        }
    }

    @Test
    void requiresCompleteCohortSamplesAndOmitsUnknownFailedAndLongTurnoverIntervals() {
        String url = System.getenv("WATTUP_TEST_DB_URL");
        if (!url.matches("jdbc:mysql://[^/?#]+/wattup_test(?:\\?[^#]*)?")) {
            throw new IllegalArgumentException("WATTUP_TEST_DB_URL must point to wattup_test");
        }
        try (var context = new SpringApplicationBuilder(WattUpApplication.class).web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + url, "--wattup.collection.enabled=false")) {
            var db = context.getBean(JdbcTemplate.class);
            var analytics = context.getBean(StationAnalyticsRepository.class);
            var fixture = new Fixture(db);
            try {
                fixture.seed();
                var all = analytics.patterns(fixture.station, ChargerProfile.ALL, Fixture.START, Fixture.day(22));
                assertThat(all).singleElement().satisfies(bucket -> {
                    assertThat(bucket.hour()).isEqualTo(12);
                    assertThat(bucket.count()).isEqualTo(2);
                    assertThat(bucket.meanAvailableRatio()).isCloseTo(0.625, within(0.0001));
                });
                var combo = analytics.patterns(fixture.station, ChargerProfile.DC_COMBO, Fixture.START, Fixture.day(22));
                assertThat(combo).singleElement().satisfies(bucket -> {
                    assertThat(bucket.count()).isEqualTo(3);
                    assertThat(bucket.meanAvailableRatio()).isCloseTo(5.0 / 6, within(0.0001));
                });
                var pairs = analytics.turnover(fixture.station, ChargerProfile.ALL, Fixture.day(30), Fixture.day(32));
                assertThat(pairs.hours()).isCloseTo(2, within(0.0001));
                assertThat(pairs.chargerHours()).isCloseTo(4, within(0.0001));
                assertThat(pairs.changes()).isEqualTo(1);
                assertThat(pairs.chargingToAvailable()).isEqualTo(1);
                assertThat(pairs.to()).isEqualTo(Fixture.day(30).plus(5, ChronoUnit.HOURS));
            } finally {
                fixture.clean();
            }
        }
    }

    private static final class Fixture {
        private static final Instant START = Instant.parse("2036-01-02T03:00:00Z");
        private static final String REGION = "ZX";
        private final JdbcTemplate db;
        private final String station = "KECO:TEST" + UUID.randomUUID().toString().substring(0, 8);
        private final java.util.List<Long> runs = new java.util.ArrayList<>();
        private long dc;
        private long ac;

        Fixture(JdbcTemplate db) {
            this.db = db;
        }

        void seed() {
            inventory();
            full(START, "AVAILABLE", "AVAILABLE");
            full(START.plus(10, ChronoUnit.MINUTES), "CHARGING", "AVAILABLE");
            full(day(7), "AVAILABLE", "CHARGING");
            full(day(14), "AVAILABLE", "UNKNOWN");
            run(day(21), "STATUS_FULL", false);
            Instant morning = day(30);
            full(morning, "CHARGING", "AVAILABLE");
            full(morning.plus(1, ChronoUnit.HOURS), "AVAILABLE", "AVAILABLE");
            run(morning.plus(90, ChronoUnit.MINUTES), "STATUS_DELTA", false);
            full(morning.plus(2, ChronoUnit.HOURS), "AVAILABLE", "AVAILABLE");
            full(morning.plus(3, ChronoUnit.HOURS), "UNKNOWN", "AVAILABLE");
            full(morning.plus(4, ChronoUnit.HOURS), "AVAILABLE", "AVAILABLE");
            full(morning.plus(5, ChronoUnit.HOURS), "AVAILABLE", "AVAILABLE");
            full(morning.plus(8, ChronoUnit.HOURS), "CHARGING", "AVAILABLE");
        }

        void inventory() {
            db.update("INSERT INTO charging_stations (station_id,source_station_id,station_name) VALUES (?,?,'Fixture')",
                    station, station.substring(5));
            dc = charger("01", "04");
            ac = charger("02", "02");
        }

        void sample(PredictionQualityRepository quality, Instant target, String model, double probability) {
            quality.capture(List.of(new PredictionQualityRepository.Sample(station, ChargerProfile.ALL, model,
                    "daegu-eta-v4", target.minusSeconds(900), target.minusSeconds(60), target, probability,
                    List.of(Long.toString(dc), Long.toString(ac)))));
        }

        void clean() {
            db.update("DELETE FROM charger_status_observations WHERE charger_id IN (?,?)", dc, ac);
            db.update("DELETE FROM chargers WHERE station_id=?", station);
            db.update("DELETE FROM charging_stations WHERE station_id=?", station);
            runs.forEach(id -> db.update("DELETE FROM collection_runs WHERE run_id=?", id));
        }

        long charger(String sourceId, String code) {
            return insert("""
                    INSERT INTO chargers (station_id,source_charger_id,charger_type_code,inventory_region)
                    VALUES (?,?,?,?)
                    """, station, sourceId, code, REGION);
        }

        void full(Instant at, String dcStatus, String acStatus) {
            long run = run(at, "STATUS_FULL", true);
            observation(at, run, dc, dcStatus);
            observation(at, run, ac, acStatus);
        }

        long run(Instant completed, String type, boolean complete) {
            long run = insert("""
                    INSERT INTO collection_runs (run_type,status,region_code,started_at,completed_at,is_complete)
                    VALUES (?,?,?,?,?,?)
                    """, type, complete ? "SUCCEEDED" : "PARTIAL", REGION, utc(completed.minusSeconds(5)),
                    utc(completed), complete);
            runs.add(run);
            return run;
        }

        void observation(Instant at, long run, long charger, String status) {
            db.update("""
                    INSERT INTO charger_status_observations (charger_id,collection_run_id,raw_status_code,
                        normalized_status,observed_at,source_event_fingerprint)
                    VALUES (?,?,?,?,?,REPEAT('c',64))
                    """, charger, run, switch (status) {
                        case "AVAILABLE" -> "2";
                        case "CHARGING" -> "3";
                        default -> "9";
                    }, status, utc(at));
        }

        long insert(String query, Object... values) {
            var key = new GeneratedKeyHolder();
            db.update(connection -> {
                var stmt = connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
                for (int i = 0; i < values.length; i++) {
                    stmt.setObject(i + 1, values[i]);
                }
                return stmt;
            }, key);
            return Objects.requireNonNull(key.getKey()).longValue();
        }

        static Instant day(int offset) {
            return START.plus(offset, ChronoUnit.DAYS);
        }

        static LocalDateTime utc(Instant at) {
            return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
        }
    }
}
