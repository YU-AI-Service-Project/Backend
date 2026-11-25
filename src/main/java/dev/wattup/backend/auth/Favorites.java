package dev.wattup.backend.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
class Favorites {
    private static final Pattern STATION_ID = Pattern.compile("KECO:[A-Za-z0-9_-]{1,80}");
    private final NamedParameterJdbcTemplate db;
    private final AuthSessions sessions;

    Favorites(NamedParameterJdbcTemplate db, AuthSessions sessions) {
        this.db = db;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    Page page(Jwt access, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
        long id = AuthSessions.userId(access);
        long offset = (long) page * size;
        long total = db.queryForObject("SELECT COUNT(*) FROM user_favorites WHERE user_id=:id",
                Map.of("id", id), Long.class);
        if (offset >= total) {
            return new Page(List.of(), page, size, total, false);
        }
        LocalDateTime freshAfter = AuthValues.utcNow().minusMinutes(10);
        var items = db.query("""
                WITH selected AS (
                    SELECT favorite_id, station_id, created_at FROM user_favorites
                    WHERE user_id=:id ORDER BY created_at DESC, favorite_id DESC LIMIT :limit OFFSET :offset
                )
                SELECT f.station_id, f.created_at, s.station_id IS NOT NULL, s.station_name, s.address,
                    COUNT(c.charger_id), COUNT(cs.last_observed_at), MIN(cs.last_observed_at),
                    COUNT(CASE WHEN cs.normalized_status='AVAILABLE' THEN 1 END)
                FROM selected f
                LEFT JOIN charging_stations s
                  ON s.station_id=f.station_id AND s.source_deleted=FALSE AND s.source_limited=FALSE
                LEFT JOIN chargers c ON c.station_id=s.station_id AND c.source_deleted=FALSE AND c.source_limited=FALSE
                LEFT JOIN charger_current_status cs ON cs.charger_id=c.charger_id
                GROUP BY f.favorite_id, f.station_id, f.created_at, s.station_id, s.station_name, s.address
                ORDER BY f.created_at DESC, f.favorite_id DESC
                """, Map.of("id", id, "limit", size, "offset", offset), (rs, i) -> {
            boolean visible = rs.getBoolean(3);
            LocalDateTime oldest = rs.getObject(8, LocalDateTime.class);
            String freshness = oldest == null ? "UNKNOWN" : !oldest.isAfter(freshAfter) ? "STALE"
                    : rs.getInt(6) == rs.getInt(7) ? "FRESH" : "UNKNOWN";
            var current = visible ? new Current(rs.getInt(9), freshness) : null;
            return new Item(rs.getString(1), rs.getString(4), rs.getString(5),
                    rs.getObject(2, LocalDateTime.class).toInstant(ZoneOffset.UTC),
                    visible ? "ACTIVE" : "UNAVAILABLE", current);
        });
        return new Page(items, page, size, total, offset + items.size() < total);
    }

    @Transactional
    Saved add(Jwt access, String station) {
        requireStationId(station);
        long id = sessions.lockCurrent(access);
        if (!publicStations(Set.of(station)).contains(station)) {
            throw new AuthFailure(404, "STATION_NOT_FOUND");
        }
        LocalDateTime now = now();
        try {
            db.update("INSERT INTO user_favorites (user_id,station_id,created_at) VALUES (:id,:station,:now)",
                    Map.of("id", id, "station", station, "now", now));
        } catch (DuplicateKeyException e) {
            throw new AuthFailure(409, "FAVORITE_ALREADY_EXISTS");
        }
        return new Saved(station, now.toInstant(ZoneOffset.UTC));
    }

    @Transactional
    void remove(Jwt access, String station) {
        requireStationId(station);
        long id = sessions.lockCurrent(access);
        db.update("DELETE FROM user_favorites WHERE user_id=:id AND station_id=:station AND BINARY station_id=BINARY :station",
                Map.of("id", id, "station", station));
    }

    @Transactional
    ImportResult merge(Jwt access, List<String> stationIds, String mode) {
        if (stationIds == null || stationIds.size() > 100 || !"MERGE".equals(mode)) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
        long id = sessions.lockCurrent(access);
        var submitted = new HashSet<String>();
        stationIds.stream().filter(Favorites::validStationId).forEach(submitted::add);
        if (submitted.isEmpty()) {
            return new ImportResult(0, 0, stationIds.size());
        }
        var known = new HashSet<>(db.queryForList("""
                SELECT station_id FROM user_favorites WHERE user_id=:id AND station_id IN (:stations)
                """, Map.of("id", id, "stations", submitted), String.class));
        submitted.removeAll(known);
        var available = submitted.isEmpty() ? Set.<String>of() : publicStations(submitted);
        var insertions = new ArrayList<MapSqlParameterSource>();
        LocalDateTime now = now();
        int invalid = 0;
        int existing = 0;
        for (String station : stationIds) {
            if (known.contains(station)) {
                existing++;
            } else if (station == null || !available.contains(station)) {
                invalid++;
            } else {
                known.add(station);
                insertions.add(new MapSqlParameterSource(Map.of("id", id, "station", station, "now", now)));
            }
        }
        if (!insertions.isEmpty()) {
            db.batchUpdate("INSERT INTO user_favorites (user_id,station_id,created_at) VALUES (:id,:station,:now)",
                    insertions.toArray(MapSqlParameterSource[]::new));
        }
        return new ImportResult(insertions.size(), existing, invalid);
    }

    private Set<String> publicStations(Set<String> stations) {
        return new HashSet<>(db.queryForList("""
                SELECT station_id FROM charging_stations WHERE station_id IN (:stations)
                    AND source_deleted=FALSE AND source_limited=FALSE
                """, Map.of("stations", stations), String.class));
    }

    private static LocalDateTime now() {
        return AuthValues.utcNow().truncatedTo(ChronoUnit.MILLIS);
    }

    private static void requireStationId(String station) {
        if (!validStationId(station)) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
    }

    private static boolean validStationId(String station) {
        return station != null && STATION_ID.matcher(station).matches();
    }

    record Page(List<Item> items, int page, int size, long total, boolean hasNext) {}
    record Item(String stationId, String name, String address, Instant createdAt, String status, Current current) {}
    record Current(int availableCount, String freshness) {}
    record Saved(String stationId, Instant createdAt) {}
    record ImportResult(int imported, int alreadyExisted, int invalid) {}
}
