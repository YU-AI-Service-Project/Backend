package dev.wattup.backend.prediction;

import dev.wattup.backend.station.ChargerProfile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Repository
class EtaHistoryRepository {
    private static final String INVENTORY = """
            FROM charging_stations s
            LEFT JOIN chargers c ON c.station_id = s.station_id
              AND c.source_limited = FALSE AND c.source_deleted = FALSE
            WHERE s.station_id IN (:stations) AND s.source_limited = FALSE AND s.source_deleted = FALSE
            """;

    private final NamedParameterJdbcTemplate jdbc;

    EtaHistoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, timeout = 4)
    Map<String, Candidate> load(List<String> stationIds, ChargerProfile profile, Instant asOf) {
        var params = new MapSqlParameterSource("stations", stationIds)
                .addValue("asOf", LocalDateTime.ofInstant(asOf, ZoneOffset.UTC))
                .addValue("windowStart", LocalDateTime.ofInstant(asOf.minusSeconds(1800), ZoneOffset.UTC));
        var accumulators = new HashMap<String, History>();
        jdbc.query("""
                WITH RECURSIVE sample_grid AS (
                  SELECT 0 AS sample_index, CAST(:windowStart AS DATETIME(3)) AS sample_time
                  UNION ALL
                  SELECT sample_index + 1, sample_time + INTERVAL 5 MINUTE FROM sample_grid WHERE sample_index < 6
                ), inventory AS (
                  SELECT s.station_id, s.latitude, s.longitude, c.charger_id, c.inventory_region,
                    c.charger_type_code, c.output_kw, c.inventory_run_id
                """ + INVENTORY + """
                )
                SELECT c.*, g.sample_index, o.raw_status_code, o.charge_started_at,
                  CASE WHEN g.sample_index = 6 THEN (
                    SELECT MAX(delta.completed_at) FROM collection_runs delta
                    WHERE delta.region_code = c.inventory_region AND delta.run_type = 'STATUS_DELTA'
                      AND delta.is_complete = TRUE AND delta.status = 'SUCCEEDED' AND delta.completed_at <= :asOf
                  ) END AS status_poll_at,
                  NOT EXISTS (
                    SELECT 1 FROM collection_runs newer_metadata
                    WHERE newer_metadata.run_type = 'STATUS_FULL' AND newer_metadata.region_code = c.inventory_region
                      AND newer_metadata.completed_at > :asOf AND newer_metadata.is_complete = TRUE
                      AND newer_metadata.status = 'SUCCEEDED'
                  ) AND (c.inventory_run_id IS NULL OR EXISTS (
                    SELECT 1 FROM collection_runs metadata
                    WHERE metadata.run_id = c.inventory_run_id AND metadata.completed_at <= :asOf
                      AND metadata.is_complete = TRUE AND metadata.status = 'SUCCEEDED'
                  )) AND EXISTS (
                    SELECT 1 FROM collection_runs delta
                    WHERE delta.region_code = c.inventory_region AND delta.run_type = 'STATUS_DELTA'
                      AND delta.is_complete = TRUE AND delta.status = 'SUCCEEDED'
                      AND delta.completed_at BETWEEN g.sample_time - INTERVAL 450 SECOND AND g.sample_time
                  ) AND EXISTS (
                    SELECT 1 FROM collection_runs full_run
                    WHERE full_run.region_code = c.inventory_region AND full_run.run_type = 'STATUS_FULL'
                      AND full_run.is_complete = TRUE AND full_run.status = 'SUCCEEDED'
                      AND full_run.completed_at BETWEEN g.sample_time - INTERVAL 4500 SECOND AND g.sample_time
                      AND NOT EXISTS (
                        SELECT 1 FROM collection_runs bad
                        WHERE bad.region_code = c.inventory_region AND bad.started_at <= g.sample_time
                          AND bad.completed_at >= full_run.completed_at
                          AND bad.run_type IN ('STATUS_FULL', 'STATUS_DELTA') AND bad.is_complete = FALSE
                      )
                  ) AS reliable
                FROM inventory c
                CROSS JOIN sample_grid g
                LEFT JOIN charger_status_observations o ON o.observation_id = (
                  SELECT h.observation_id FROM charger_status_observations h
                  JOIN collection_runs r ON h.collection_run_id = r.run_id
                  WHERE h.charger_id = c.charger_id AND h.used_for_current = TRUE
                    AND r.status = 'SUCCEEDED' AND r.is_complete = TRUE
                    AND r.region_code = c.inventory_region
                    AND h.observed_at <= g.sample_time AND r.completed_at <= g.sample_time
                  ORDER BY h.observed_at DESC, h.observation_id DESC
                  LIMIT 1
                )
                """, params, (RowCallbackHandler) row -> addSample(row, profile, accumulators));
        if (accumulators.values().stream().anyMatch(history -> history.reliableSamples() == EtaFeatures.SAMPLE_COUNT)) {
            jdbc.query("""
                    WITH inventory AS (
                      SELECT s.station_id, c.charger_id, c.charger_type_code, c.inventory_region
                    """ + INVENTORY + """
                    )
                    SELECT c.station_id, c.charger_type_code, COUNT(*) AS changes
                    FROM inventory c
                    JOIN charger_state_changes ch ON ch.charger_id = c.charger_id
                    JOIN charger_status_observations o ON o.observation_id = ch.new_observation_id
                    JOIN collection_runs r ON r.run_id = o.collection_run_id
                    WHERE r.status = 'SUCCEEDED' AND r.is_complete = TRUE
                      AND r.region_code = c.inventory_region AND o.used_for_current = TRUE
                      AND o.observed_at <= :asOf AND r.completed_at > :windowStart AND r.completed_at <= :asOf
                    GROUP BY c.station_id, c.charger_type_code
                    """, params, (RowCallbackHandler) row -> {
                if (profile.includes(row.getString("charger_type_code"))) {
                    accumulators.get(row.getString("station_id")).changes += row.getInt("changes");
                }
            });
        }
        var candidates = new HashMap<String, Candidate>();
        accumulators.forEach((station, history) -> candidates.put(station,
                new Candidate(history.latitude, history.longitude, history.statusPollAt,
                        EtaFeatures.build(asOf, profile, history.samples,
                        history.reliableSamples(), history.changes).orElseGet(Map::of))));
        return candidates;
    }

    private static void addSample(ResultSet row, ChargerProfile profile, Map<String, History> histories)
            throws SQLException {
        String station = row.getString("station_id");
        History history = histories.get(station);
        if (history == null) {
            history = new History(row.getObject("latitude", Double.class), row.getObject("longitude", Double.class));
            histories.put(station, history);
        }
        String code = row.getString("charger_type_code");
        if (row.getObject("charger_id") == null || !profile.includes(code)) {
            return;
        }
        int sampleIndex = row.getInt("sample_index");
        String status = row.getString("raw_status_code");
        if (status == null && sampleIndex < EtaFeatures.SAMPLE_COUNT - 1) {
            return;
        }
        LocalDateTime chargeStartedAt = row.getObject("charge_started_at", LocalDateTime.class);
        history.samples.get(sampleIndex).add(new EtaFeatures.Reading(status, code,
                row.getDouble("output_kw"), chargeStartedAt == null ? null : chargeStartedAt.toInstant(ZoneOffset.UTC)));
        if (!row.getBoolean("reliable")) {
            history.unreliable[sampleIndex] = true;
        }
        LocalDateTime lastPoll = row.getObject("status_poll_at", LocalDateTime.class);
        if (lastPoll != null) {
            Instant pollAt = lastPoll.toInstant(ZoneOffset.UTC);
            if (history.statusPollAt == null || pollAt.isBefore(history.statusPollAt)) {
                history.statusPollAt = pollAt;
            }
        }
    }

    record Candidate(Double latitude, Double longitude, Instant statusPollAt, Map<String, Number> features) {
    }

    private static final class History {
        final Double latitude;
        final Double longitude;
        final List<List<EtaFeatures.Reading>> samples = IntStream.range(0, EtaFeatures.SAMPLE_COUNT)
                .<List<EtaFeatures.Reading>>mapToObj(i -> new ArrayList<>()).toList();
        final boolean[] unreliable = new boolean[EtaFeatures.SAMPLE_COUNT];
        Instant statusPollAt;
        int changes;

        History(Double latitude, Double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        int reliableSamples() {
            return (int) IntStream.range(0, EtaFeatures.SAMPLE_COUNT).filter(i -> !unreliable[i]).count();
        }
    }
}
