package dev.wattup.backend.station;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
class StationAnalyticsRepository {
    private static final String SNAPSHOTS = """
            WITH cohort AS (
                SELECT c.charger_id, c.inventory_region
                FROM chargers c JOIN charging_stations s ON s.station_id = c.station_id
                WHERE c.station_id = :station AND NOT c.source_deleted AND NOT c.source_limited
                  AND NOT s.source_deleted AND NOT s.source_limited %s
            ), scope AS (
                SELECT MIN(inventory_region) AS region, COUNT(*) AS charger_count
                FROM cohort HAVING COUNT(DISTINCT inventory_region) = 1 AND COUNT(inventory_region) = COUNT(*)
            ), full_runs AS (
                SELECT r.run_id, r.completed_at, scope.region, scope.charger_count
                FROM scope JOIN collection_runs r ON r.region_code = scope.region
                WHERE r.run_type = 'STATUS_FULL' AND r.status = 'SUCCEEDED' AND r.is_complete = TRUE
                  AND r.completed_at >= :fromTime AND r.completed_at <= :toTime
            ), readings AS (
                SELECT g.run_id, c.charger_id, o.normalized_status
                FROM full_runs g CROSS JOIN cohort c
                JOIN charger_status_observations o ON o.charger_id = c.charger_id AND o.collection_run_id = g.run_id
                WHERE o.used_for_current = TRUE AND o.normalized_status <> 'UNKNOWN'
                  AND o.observed_at >= :fromTime AND o.observed_at <= g.completed_at
            ), snapshots AS (
                SELECT g.run_id, g.completed_at, g.region, g.charger_count,
                  COUNT(r.charger_id) = g.charger_count AND COUNT(DISTINCT r.charger_id) = g.charger_count AS reliable,
                  COUNT(CASE WHEN r.normalized_status = 'AVAILABLE' THEN 1 END) / g.charger_count AS available_ratio
                FROM full_runs g LEFT JOIN readings r ON r.run_id = g.run_id
                GROUP BY g.run_id, g.completed_at, g.region, g.charger_count
            )
            """;

    private final NamedParameterJdbcTemplate db;

    StationAnalyticsRepository(NamedParameterJdbcTemplate db) {
        this.db = db;
    }

    @Transactional(readOnly = true, timeout = 4)
    List<Bucket> patterns(String stationId, ChargerProfile profile, Instant from, Instant to) {
        var query = snapshotSql(profile) + """
                , local_hours AS (
                    SELECT DATE(DATE_ADD(completed_at, INTERVAL 9 HOUR)) AS local_day,
                      HOUR(DATE_ADD(completed_at, INTERVAL 9 HOUR)) AS local_hour,
                      AVG(available_ratio) AS daily_ratio, MIN(completed_at) AS first_sample,
                      MAX(completed_at) AS last_sample
                    FROM snapshots WHERE reliable GROUP BY local_day, local_hour
                )
                SELECT WEEKDAY(local_day) + 1 AS iso_weekday, local_hour, COUNT(*), AVG(daily_ratio),
                    MIN(first_sample), MAX(last_sample)
                FROM local_hours GROUP BY iso_weekday, local_hour ORDER BY iso_weekday, local_hour
                """;
        return db.query(query, params(stationId, profile, from, to), (rs, i) -> new Bucket(
                rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getDouble(4), instant(rs, 5), instant(rs, 6)));
    }

    @Transactional(readOnly = true, timeout = 4)
    Turnover turnover(String stationId, ChargerProfile profile, Instant from, Instant to) {
        var query = snapshotSql(profile) + """
                , ordered AS (
                    SELECT snapshots.*,
                        LAG(run_id) OVER by_time AS previous_run,
                        LAG(completed_at) OVER by_time AS previous_time,
                        LAG(reliable) OVER by_time AS previous_reliable
                    FROM snapshots WINDOW by_time AS (ORDER BY completed_at, run_id)
                ), pairs AS (
                    SELECT run_id, previous_run, previous_time, completed_at, charger_count,
                        TIMESTAMPDIFF(MICROSECOND, previous_time, completed_at) / 3600000000.0 AS hours
                    FROM ordered p
                    WHERE reliable AND previous_reliable AND completed_at > previous_time
                      AND completed_at <= previous_time + INTERVAL 75 MINUTE
                      AND NOT EXISTS (
                        SELECT 1 FROM collection_runs bad WHERE bad.region_code = p.region
                          AND bad.run_type IN ('STATUS_FULL', 'STATUS_DELTA') AND bad.is_complete = FALSE
                          AND bad.status <> 'QUEUED'
                          AND bad.started_at < p.completed_at
                          AND COALESCE(bad.completed_at, :toTime) > p.previous_time
                      )
                ), coverage AS (
                    SELECT SUM(hours) AS observed_hours, SUM(hours * charger_count) AS charger_hours,
                        COUNT(DISTINCT DATE(DATE_ADD(completed_at, INTERVAL 9 HOUR))) AS days_observed,
                        MIN(previous_time) AS first_sample, MAX(completed_at) AS last_sample FROM pairs
                ), changes AS (
                    SELECT COUNT(CASE WHEN old_read.normalized_status <> new_read.normalized_status THEN 1 END)
                        AS observed_changes,
                        COUNT(CASE WHEN old_read.normalized_status = 'CHARGING'
                          AND new_read.normalized_status = 'AVAILABLE' THEN 1 END)
                        AS charging_to_available
                    FROM pairs p JOIN readings old_read ON old_read.run_id = p.previous_run
                    JOIN readings new_read ON new_read.run_id = p.run_id AND new_read.charger_id = old_read.charger_id
                )
                SELECT observed_hours, charger_hours, days_observed, observed_changes, charging_to_available,
                    first_sample, last_sample
                FROM coverage CROSS JOIN changes
                """;
        return db.queryForObject(query, params(stationId, profile, from, to), (rs, i) -> new Turnover(
                rs.getDouble(1), rs.getDouble(2), rs.getInt(3), rs.getLong(4), rs.getLong(5),
                instant(rs, 6), instant(rs, 7)));
    }

    private static String snapshotSql(ChargerProfile profile) {
        return SNAPSHOTS.formatted(profile.typeCodes.isEmpty() ? "" : "AND c.charger_type_code IN (:types)");
    }

    private static MapSqlParameterSource params(String stationId, ChargerProfile profile, Instant from, Instant to) {
        return new MapSqlParameterSource("station", stationId).addValue("types", profile.typeCodes)
                .addValue("fromTime", LocalDateTime.ofInstant(from, ZoneOffset.UTC))
                .addValue("toTime", LocalDateTime.ofInstant(to, ZoneOffset.UTC));
    }

    private static Instant instant(ResultSet row, int index) throws SQLException {
        LocalDateTime value = row.getObject(index, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    record Bucket(int weekday, int hour, int count, double meanAvailableRatio, Instant from, Instant to) {}
    record Turnover(double hours, double chargerHours, int days, long changes, long chargingToAvailable,
                    Instant from, Instant to) {}
}
