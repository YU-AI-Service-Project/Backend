package dev.wattup.backend.collection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

final class ChargerCollectionRepository {
    private final JdbcTemplate jdbc;

    ChargerCollectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    int publish(long runId, KecoEndpoint endpoint, List<ObservedReading> batch) {
        if (endpoint == KecoEndpoint.FULL_INVENTORY) {
            saveInventory(batch);
        }
        for (ObservedReading reading : batch) {
            saveObservation(runId, reading);
        }
        return batch.size();
    }

    private void saveInventory(List<ObservedReading> batch) {
        var stations = batch.stream().collect(Collectors.groupingBy(
                observation -> observation.reading().internalStationId(), LinkedHashMap::new, Collectors.toList()));
        stations.values().forEach(this::upsertStation);
        jdbc.batchUpdate("""
                INSERT INTO chargers (station_id, source_charger_id, charger_type_code, output_kw, charging_method,
                    source_deleted, source_limited, last_metadata_seen_at, source_payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) AS incoming
                ON DUPLICATE KEY UPDATE charger_type_code = incoming.charger_type_code, output_kw = incoming.output_kw,
                    charging_method = incoming.charging_method, source_deleted = incoming.source_deleted,
                    source_limited = incoming.source_limited, last_metadata_seen_at = incoming.last_metadata_seen_at,
                    source_payload = incoming.source_payload
                """, batch, 500, (statement, observation) -> {
            KecoReading reading = observation.reading();
            JsonNode row = reading.attributes();
            statement.setString(1, reading.internalStationId());
            statement.setString(2, reading.chargerId());
            statement.setString(3, attribute(row, "chgerType"));
            statement.setBigDecimal(4, decimal(row, "output", 0, 999999));
            statement.setString(5, attribute(row, "method"));
            statement.setBoolean(6, isYes(row, "delYn"));
            statement.setBoolean(7, isYes(row, "limitYn"));
            statement.setObject(8, observation.observedAt());
            statement.setString(9, row.toString());
        });
    }

    private void upsertStation(List<ObservedReading> chargers) {
        ObservedReading first = chargers.getFirst();
        KecoReading reading = first.reading();
        JsonNode row = reading.attributes();
        String stationName = attribute(row, "statNm");
        if (stationName == null) {
            throw new KecoException("UPSTREAM_STATION_NAME_MISSING", false);
        }
        boolean deleted = chargers.stream().allMatch(charger -> isYes(charger.reading().attributes(), "delYn"));
        boolean restricted = chargers.stream().allMatch(charger -> isYes(charger.reading().attributes(), "limitYn"));
        jdbc.update("""
                INSERT INTO charging_stations (station_id, source_station_id, station_name, address, latitude, longitude,
                    operator_id, operator_name, operator_phone, operating_hours, parking_free_code, access_restriction,
                    source_deleted, source_limited, last_metadata_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS incoming
                ON DUPLICATE KEY UPDATE station_name = incoming.station_name, address = incoming.address,
                    latitude = incoming.latitude, longitude = incoming.longitude, operator_id = incoming.operator_id,
                    operator_name = incoming.operator_name, operator_phone = incoming.operator_phone,
                    operating_hours = incoming.operating_hours, parking_free_code = incoming.parking_free_code,
                    access_restriction = incoming.access_restriction, source_deleted = incoming.source_deleted,
                    source_limited = incoming.source_limited, last_metadata_seen_at = incoming.last_metadata_seen_at
                """, reading.internalStationId(), reading.stationId(), stationName, attribute(row, "addr"),
                decimal(row, "lat", -90, 90), decimal(row, "lng", -180, 180), attribute(row, "busiId"),
                attribute(row, "busiNm"), attribute(row, "busiCall"), attribute(row, "useTime"),
                attribute(row, "parkingFree"), attribute(row, "limitDetail"), deleted, restricted, first.observedAt());
    }

    private void saveObservation(long runId, ObservedReading observation) {
        KecoReading reading = observation.reading();
        long chargerId = jdbc.query("""
                SELECT charger_id FROM chargers WHERE station_id = ? AND source_charger_id = ?
                """, (rs, index) -> rs.getLong("charger_id"), reading.internalStationId(), reading.chargerId())
                .stream().findFirst().orElseThrow(() -> new KecoException("CHARGER_NOT_IN_INVENTORY", false));
        CurrentStatus previous = currentStatus(chargerId);
        boolean advances = advancesCurrent(observation, previous);
        long observationId = insertObservation(runId, observation, chargerId, advances);
        if (advances) {
            updateCurrent(chargerId, observationId, observation, previous);
        }
    }

    private long insertObservation(long runId, ObservedReading observation, long chargerId, boolean advances) {
        KecoReading reading = observation.reading();
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO charger_status_observations (charger_id, collection_run_id, raw_status_code,
                        normalized_status, provider_status_updated_at, charge_started_at, observed_at, used_for_current,
                        source_event_fingerprint)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, chargerId);
            statement.setLong(2, runId);
            statement.setString(3, reading.rawStatus());
            statement.setString(4, reading.status().name());
            statement.setObject(5, reading.providerUpdatedAt());
            statement.setObject(6, reading.chargeStartedAt());
            statement.setObject(7, observation.observedAt());
            statement.setBoolean(8, advances);
            statement.setString(9, fingerprint(reading));
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private CurrentStatus currentStatus(long chargerId) {
        return jdbc.query("""
                SELECT latest_observation_id, raw_status_code,
                  COALESCE(c.provider_status_updated_at, (
                    SELECT MAX(o.provider_status_updated_at) FROM charger_status_observations o
                    WHERE o.charger_id = c.charger_id AND o.used_for_current = TRUE
                  )), c.last_observed_at
                FROM charger_current_status c WHERE charger_id = ?
                """, (rs, index) -> new CurrentStatus(rs.getLong(1), rs.getString(2),
                rs.getObject(3, LocalDateTime.class), rs.getObject(4, LocalDateTime.class)), chargerId)
                .stream().findFirst().orElse(null);
    }

    private void updateCurrent(long chargerId, long observationId, ObservedReading observation, CurrentStatus previous) {
        KecoReading reading = observation.reading();
        LocalDateTime updated = reading.providerUpdatedAt();
        jdbc.update("""
                INSERT INTO charger_current_status (charger_id, latest_observation_id, raw_status_code,
                  normalized_status, provider_status_updated_at, last_observed_at)
                VALUES (?, ?, ?, ?, ?, ?) AS incoming
                ON DUPLICATE KEY UPDATE latest_observation_id = incoming.latest_observation_id,
                  raw_status_code = incoming.raw_status_code, normalized_status = incoming.normalized_status,
                  provider_status_updated_at = incoming.provider_status_updated_at,
                  last_observed_at = incoming.last_observed_at
                """, chargerId, observationId, reading.rawStatus(), reading.status().name(), updated,
                observation.observedAt());
        if (previous != null && !previous.rawStatus().equals(reading.rawStatus())) {
            jdbc.update("""
                    INSERT INTO charger_state_changes (charger_id, previous_observation_id, new_observation_id,
                      previous_status_code, new_status_code, change_observed_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, chargerId, previous.observationId(), observationId, previous.rawStatus(),
                    reading.rawStatus(), observation.observedAt());
        }
    }

    private static boolean advancesCurrent(ObservedReading observation, CurrentStatus previous) {
        LocalDateTime updated = observation.reading().providerUpdatedAt();
        if (updated != null && updated.isAfter(observation.observedAt())) {
            return false;
        }
        if (previous == null) {
            return true;
        }
        return !observation.observedAt().isBefore(previous.observedAt())
                && (updated == null || previous.providerUpdatedAt() == null || !updated.isBefore(previous.providerUpdatedAt()));
    }

    private static String fingerprint(KecoReading reading) {
        String fields = "%s|%s|%s".formatted(reading.rawStatus(), reading.providerUpdatedAt(), reading.chargeStartedAt());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(fields.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String attribute(JsonNode row, String key) {
        String value = row.path(key).asText("").strip();
        return value.isEmpty() ? null : value;
    }

    private static boolean isYes(JsonNode row, String key) {
        return "Y".equalsIgnoreCase(attribute(row, key));
    }

    private static BigDecimal decimal(JsonNode row, String key, int minimum, int maximum) {
        String value = attribute(row, key);
        if (value == null) {
            return null;
        }
        try {
            BigDecimal number = new BigDecimal(value);
            return number.compareTo(BigDecimal.valueOf(minimum)) >= 0
                    && number.compareTo(BigDecimal.valueOf(maximum)) <= 0 ? number : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record CurrentStatus(long observationId, String rawStatus, LocalDateTime providerUpdatedAt,
                                 LocalDateTime observedAt) {
    }
}
