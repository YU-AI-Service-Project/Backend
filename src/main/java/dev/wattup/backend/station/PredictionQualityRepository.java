package dev.wattup.backend.station;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Repository
public class PredictionQualityRepository {
    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    PredictionQualityRepository(NamedParameterJdbcTemplate named, JdbcTemplate jdbc, JsonMapper json) {
        this.named = named;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(timeout = 1)
    public void capture(List<Sample> samples) {
        if (samples.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO eta_prediction_samples (station_id,charger_profile,model_version,feature_version,
                    predicted_at,requested_arrival_at,forecast_at,probability,charger_ids)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE sample_id=sample_id
                """, samples.stream().sorted(Comparator.comparing(Sample::stationId).thenComparing(Sample::profile)
                .thenComparing(Sample::forecastAt).thenComparing(Sample::modelVersion).thenComparing(Sample::featureVersion))
                .map(sample -> new Object[]{
                sample.stationId(), sample.profile().name(), sample.modelVersion(), sample.featureVersion(),
                utc(sample.predictedAt()), utc(sample.requestedArrivalAt()), utc(sample.forecastAt()),
                sample.probability(), json.writeValueAsString(sample.chargerIds())
        }).toList());
    }

    @Transactional(readOnly = true, timeout = 4)
    Evaluation evaluate(String station, ChargerProfile profile, Instant from, Instant to) {
        var parameters = new MapSqlParameterSource("station", station).addValue("profile", profile.name())
                .addValue("fromTime", utc(from)).addValue("toTime", utc(to));
        String typeFilter = profile == ChargerProfile.ALL ? "" : "AND c.charger_type_code IN (:types)";
        if (profile != ChargerProfile.ALL) {
            parameters.addValue("types", profile.typeCodes);
        }
        var rows = named.query("""
                WITH samples AS (
                    SELECT sample_id, forecast_at, predicted_at, model_version, feature_version, probability, charger_ids
                    FROM eta_prediction_samples
                    WHERE station_id=:station AND charger_profile=:profile
                      AND forecast_at>=:fromTime AND forecast_at<=:toTime
                ), selected_model AS (
                    SELECT model_version, feature_version FROM samples
                    ORDER BY predicted_at DESC, sample_id DESC LIMIT 1
                ), cohort AS (
                    SELECT c.charger_id, c.inventory_region FROM chargers c
                    WHERE c.station_id=:station AND NOT c.source_limited AND NOT c.source_deleted %s
                ), scope AS (
                    SELECT MIN(inventory_region) AS region, COUNT(*) AS size FROM cohort
                    HAVING COUNT(DISTINCT inventory_region)=1 AND COUNT(inventory_region)=COUNT(*)
                ), candidates AS (
                    SELECT p.sample_id, p.forecast_at, p.probability, scope.size,
                        (SELECT r.run_id FROM collection_runs r
                         WHERE r.run_type='STATUS_FULL' AND r.status='SUCCEEDED' AND r.is_complete=TRUE
                           AND r.region_code=scope.region AND r.completed_at>=p.forecast_at
                           AND r.completed_at<p.forecast_at + INTERVAL 5 MINUTE AND r.completed_at<=:toTime
                         ORDER BY r.completed_at, r.run_id LIMIT 1) AS run_id
                    FROM samples p JOIN selected_model m USING (model_version,feature_version) CROSS JOIN scope
                    WHERE JSON_LENGTH(p.charger_ids)=scope.size AND NOT EXISTS (
                        SELECT 1 FROM cohort c WHERE NOT JSON_CONTAINS(p.charger_ids,JSON_QUOTE(CAST(c.charger_id AS CHAR)))
                    )
                ), matched AS (
                    SELECT p.sample_id, p.forecast_at, LEAST(4,FLOOR(p.probability*5)) AS bin,
                        MAX(o.normalized_status='AVAILABLE') AS available
                    FROM candidates p JOIN collection_runs r ON r.run_id=p.run_id
                    CROSS JOIN cohort c JOIN charger_status_observations o
                      ON o.collection_run_id=r.run_id AND o.charger_id=c.charger_id
                    WHERE o.used_for_current=TRUE AND o.observed_at>=p.forecast_at AND o.observed_at<=r.completed_at
                    GROUP BY p.sample_id,p.forecast_at,p.probability,p.size
                    HAVING COUNT(*)=p.size AND COUNT(DISTINCT o.charger_id)=p.size
                      AND SUM(o.normalized_status='UNKNOWN')=0
                ), totals AS (
                    SELECT COUNT(*) AS sample_count, COUNT(DISTINCT DATE(forecast_at + INTERVAL 9 HOUR)) AS day_count
                    FROM matched
                ), bins AS (
                    SELECT bin, COUNT(*) AS bin_count, AVG(available) AS observed_fraction FROM matched GROUP BY bin
                )
                SELECT m.model_version,m.feature_version,t.sample_count,t.day_count,b.bin,b.bin_count,b.observed_fraction
                FROM selected_model m CROSS JOIN totals t LEFT JOIN bins b ON TRUE ORDER BY b.bin
                """.formatted(typeFilter), parameters, (row, index) -> new EvaluationRow(
                row.getString("model_version"), row.getString("feature_version"),
                row.getLong("sample_count"), row.getLong("day_count"),
                row.getObject("bin") == null ? null : new Bucket(row.getInt("bin"), row.getLong("bin_count"),
                        row.getDouble("observed_fraction"))));
        if (rows.isEmpty()) {
            return new Evaluation(null, null, 0, 0, List.of());
        }
        var head = rows.getFirst();
        var buckets = rows.stream().map(EvaluationRow::bucket).filter(Objects::nonNull).toList();
        return new Evaluation(head.modelVersion(), head.featureVersion(), head.sampleCount(), head.dayCount(), buckets);
    }

    @Transactional(timeout = 4)
    public void prune(Instant cutoff) {
        jdbc.update("DELETE FROM eta_prediction_samples WHERE forecast_at < ? ORDER BY forecast_at LIMIT 10000", utc(cutoff));
    }

    private static LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    public record Sample(String stationId, ChargerProfile profile, String modelVersion, String featureVersion,
                         Instant predictedAt, Instant requestedArrivalAt, Instant forecastAt, double probability,
                         List<String> chargerIds) {}

    record Evaluation(String modelVersion, String featureVersion, long sampleCount, long dayCount, List<Bucket> buckets) {}
    record Bucket(int index, long sampleCount, double observedFraction) {}
    private record EvaluationRow(String modelVersion, String featureVersion, long sampleCount, long dayCount, Bucket bucket) {}
}
