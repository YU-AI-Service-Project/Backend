package dev.wattup.backend.station;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static dev.wattup.backend.station.StationViews.toInstant;

@Repository
class ChargingStationRepository {
    private static final String ACTIVE_STATION = "s.source_deleted = FALSE AND s.source_limited = FALSE";
    private static final String ACTIVE_CHARGER = "c.source_deleted = FALSE AND c.source_limited = FALSE";
    private final NamedParameterJdbcTemplate jdbc;

    ChargingStationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Nearby> search(StationSearch search, Instant freshAfter) {
        var params = new MapSqlParameterSource()
                .addValue("latitude", search.latitude())
                .addValue("longitude", search.longitude())
                .addValue("south", Math.max(-90, search.latitude() - search.latitudeDelta()))
                .addValue("north", Math.min(90, search.latitude() + search.latitudeDelta()))
                .addValue("earthRadiusM", StationSearch.EARTH_RADIUS_M)
                .addValue("radiusM", search.radiusM())
                .addValue("limit", search.limit())
                .addValue("availableOnly", search.availableOnly())
                .addValue("freshAfter", LocalDateTime.ofInstant(freshAfter, ZoneOffset.UTC));
        String filters = chargerConditions(params, search.filter());
        return jdbc.query("""
                WITH nearby AS (
                  SELECT s.station_id, s.station_name, s.address, s.latitude, s.longitude,
                    2 * :earthRadiusM * ASIN(SQRT(LEAST(1.0,
                      POW(SIN(RADIANS(s.latitude - :latitude) / 2), 2)
                      + COS(RADIANS(:latitude)) * COS(RADIANS(s.latitude))
                      * POW(SIN(RADIANS(s.longitude - :longitude) / 2), 2)))) AS distance_m
                  FROM charging_stations s
                  WHERE %s AND s.latitude BETWEEN :south AND :north AND s.longitude IS NOT NULL
                ), station_status AS (
                  SELECT n.station_id, n.station_name, n.address, n.latitude, n.longitude, n.distance_m,
                    COUNT(*) AS total_count,
                    COUNT(CASE WHEN cs.normalized_status = 'AVAILABLE' THEN 1 END) AS available_count,
                    COUNT(CASE WHEN cs.normalized_status = 'CHARGING' THEN 1 END) AS charging_count,
                    COUNT(CASE WHEN cs.normalized_status = 'UNAVAILABLE' THEN 1 END) AS other_count,
                    COUNT(CASE WHEN cs.normalized_status = 'AVAILABLE' AND cs.last_observed_at > :freshAfter
                          THEN 1 END) AS fresh_available_count,
                    COUNT(cs.last_observed_at) AS observed_count, MIN(cs.last_observed_at) AS oldest_at
                  FROM nearby n
                  JOIN chargers c ON c.station_id = n.station_id
                  LEFT JOIN charger_current_status cs ON cs.charger_id = c.charger_id
                  WHERE n.distance_m <= :radiusM AND %s
                  GROUP BY n.station_id, n.station_name, n.address, n.latitude, n.longitude, n.distance_m
                )
                SELECT station_status.*, COUNT(*) OVER () AS total_matches
                FROM station_status
                WHERE :availableOnly = FALSE OR fresh_available_count > 0
                ORDER BY distance_m, station_id
                LIMIT :limit
                """.formatted(ACTIVE_STATION, filters), params, (rs, index) -> {
            int total = rs.getInt("total_count");
            int available = rs.getInt("available_count");
            int charging = rs.getInt("charging_count");
            int other = rs.getInt("other_count");
            var current = new StationViews.Current(search.filter().profile(), total, available, charging,
                    other, total - available - charging - other);
            var status = new StationViews.StatusSummary(current, rs.getInt("observed_count"),
                    toInstant(rs.getObject("oldest_at", LocalDateTime.class)));
            return new Nearby(rs.getString("station_id"), rs.getString("station_name"), rs.getString("address"),
                    rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getDouble("distance_m"),
                    status, rs.getLong("total_matches"));
        });
    }

    Optional<Station> station(String id) {
        return jdbc.query("""
                SELECT s.station_id, s.station_name, s.address, s.latitude, s.longitude,
                  s.operator_name, s.operator_phone, s.operating_hours, s.fee_info, s.parking_free_code,
                  s.access_restriction
                FROM charging_stations s
                WHERE s.station_id = :stationId AND %s
                """.formatted(ACTIVE_STATION), new MapSqlParameterSource("stationId", id), (rs, index) ->
                new Station(rs.getString("station_id"), rs.getString("station_name"), rs.getString("address"),
                        rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("operator_name"),
                        rs.getString("operator_phone"), rs.getString("operating_hours"), rs.getString("fee_info"),
                        rs.getString("parking_free_code"), rs.getString("access_restriction")))
                .stream().findFirst();
    }

    List<StationViews.Charger> chargers(String id, ChargerFilter filter) {
        var params = new MapSqlParameterSource("stationId", id);
        String filters = chargerConditions(params, filter);
        return jdbc.query("""
                SELECT c.source_charger_id, c.charger_type_code, c.connector_type_label, c.output_kw,
                  COALESCE(cs.normalized_status, 'UNKNOWN') AS status, cs.raw_status_code, cs.last_observed_at
                FROM chargers c
                LEFT JOIN charger_current_status cs ON cs.charger_id = c.charger_id
                WHERE c.station_id = :stationId AND %s
                ORDER BY c.source_charger_id
                """.formatted(filters), params, (rs, index) -> {
            String type = rs.getString("charger_type_code");
            return new StationViews.Charger(rs.getString("source_charger_id"), type,
                    connector(rs.getString("connector_type_label"), type), rs.getBigDecimal("output_kw"),
                    rs.getString("status"), rs.getString("raw_status_code"),
                    toInstant(rs.getObject("last_observed_at", LocalDateTime.class)));
        });
    }

    private static String chargerConditions(MapSqlParameterSource params, ChargerFilter filter) {
        var conditions = new StringBuilder(ACTIVE_CHARGER);
        if (!filter.profile().typeCodes.isEmpty()) {
            params.addValue("profileTypes", filter.profile().typeCodes);
            conditions.append(" AND c.charger_type_code IN (:profileTypes)");
        }
        if (!filter.typeCodes().isEmpty()) {
            params.addValue("chargerTypes", filter.typeCodes());
            conditions.append(" AND c.charger_type_code IN (:chargerTypes)");
        }
        if (filter.minOutputKw() != null) {
            params.addValue("minOutputKw", filter.minOutputKw());
            conditions.append(" AND c.output_kw >= :minOutputKw");
        }
        return conditions.toString();
    }

    private static String connector(String storedLabel, String typeCode) {
        if (storedLabel != null || typeCode == null) {
            return storedLabel;
        }
        if (ChargerProfile.AC_SLOW.includes(typeCode)) {
            return "AC_SLOW";
        }
        return ChargerProfile.DC_COMBO.includes(typeCode) ? "DC_COMBO" : null;
    }

    record Nearby(String stationId, String name, String address, BigDecimal latitude, BigDecimal longitude,
                  double distanceM, StationViews.StatusSummary status, long totalMatches) {
    }

    record Station(String stationId, String name, String address, BigDecimal latitude, BigDecimal longitude,
                   String operatorName, String operatorPhone, String operatingHours, String feeInfo,
                   String parkingFreeCode, String usageRestrictions) {
    }
}
