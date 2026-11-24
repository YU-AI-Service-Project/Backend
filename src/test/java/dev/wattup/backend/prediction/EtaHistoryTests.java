package dev.wattup.backend.prediction;

import dev.wattup.backend.WattUpApplication;
import dev.wattup.backend.station.ChargerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "WATTUP_TEST_DB_URL", matches = ".+")
class EtaHistoryTests {
    private static final LocalDateTime GRID_END = LocalDateTime.parse("2026-11-12T01:00:00");
    private static final Instant AS_OF = GRID_END.toInstant(ZoneOffset.UTC);

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
                for (int sample = 0; sample < 7; sample++) {
                    run(jdbc, "STATUS_DELTA", "XY", GRID_END.minusMinutes(30 - sample * 5), 0, true);
                }
                long future = run(jdbc, "STATUS_DELTA", "XY", GRID_END.minusSeconds(10), 20, true);
                observe(jdbc, charger, future, "9", "UNKNOWN", GRID_END.minusSeconds(10));
                run(jdbc, "STATUS_DELTA", "ZZ", GRID_END.minusMinutes(1), 0, false);
                var result = history.load(List.of(station), ChargerProfile.AC_SLOW, AS_OF).get(station);
                assertThat(result.latitude()).isEqualTo(35.1);
                assertThat(result.features()).containsEntry("availableCount", 1).containsEntry("availableLag30", 1)
                        .containsEntry("coverageLast30m", 1.0);

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
}
