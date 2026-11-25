package dev.wattup.backend.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
class AppSupport {
    private static final String PUBLISHED = "is_active=TRUE AND published_at<=?";
    private static final Set<String> REPORT_FIELDS = Set.of("category", "stationId", "message", "contactEmail");
    private static final Set<String> CATEGORIES = Set.of("STATION_INFORMATION", "APP_ERROR", "OTHER");
    private static final Pattern STATION_ID = Pattern.compile("KECO:[A-Za-z0-9_-]{1,80}");
    private final JdbcTemplate db;
    private final AuthSessions sessions;

    AppSupport(JdbcTemplate db, AuthSessions sessions) {
        this.db = db;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    Notices notices(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
        LocalDateTime now = AuthValues.utcNow();
        long total = db.queryForObject("SELECT COUNT(*) FROM app_notices WHERE " + PUBLISHED, Long.class, now);
        long offset = (long) page * size;
        if (offset >= total) {
            return new Notices(List.of(), total);
        }
        var items = db.query("""
                SELECT notice_id,title,content,published_at,severity FROM app_notices
                WHERE %s ORDER BY published_at DESC,notice_id DESC LIMIT ? OFFSET ?
                """.formatted(PUBLISHED), (rs, i) -> new Notice(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getString(5)), now, size, offset);
        return new Notices(items, total);
    }

    @Transactional
    Received report(Jwt access, JsonNode input) {
        if (input == null || !input.isObject()
                || input.properties().stream().anyMatch(field -> !REPORT_FIELDS.contains(field.getKey()))) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
        String category = text(input.path("category"), 19, false);
        String message = text(input.path("message"), 2_000, false).strip();
        String station = text(input.path("stationId"), 85, true);
        String contact = text(input.path("contactEmail"), 254, true);
        if (!CATEGORIES.contains(category) || message.isEmpty() || (station != null && !STATION_ID.matcher(station).matches())) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
        String email = contact == null ? null : AuthValues.email(contact);
        long user = sessions.lockCurrent(access);
        if (station != null && db.queryForList("""
                SELECT station_id FROM charging_stations
                WHERE station_id=? AND BINARY station_id=BINARY ? AND source_deleted=FALSE AND source_limited=FALSE FOR SHARE
                """, String.class, station, station).isEmpty()) {
            throw new AuthFailure(404, "STATION_NOT_FOUND");
        }

        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            var insert = connection.prepareStatement("""
                    INSERT INTO support_reports (user_id,station_id,category,message,contact_email) VALUES (?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            insert.setLong(1, user);
            insert.setString(2, station);
            insert.setString(3, category);
            insert.setString(4, message);
            insert.setString(5, email);
            return insert;
        }, key);
        return new Received(Objects.requireNonNull(key.getKey()).toString(), "RECEIVED");
    }

    private static String text(JsonNode value, int maxLength, boolean optional) {
        if (optional && (value.isMissingNode() || value.isNull())) {
            return null;
        }
        if (!value.isString() || value.asText().length() > maxLength) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
        return value.asText();
    }

    record Notices(List<Notice> items, long total) {}
    record Notice(String noticeId, String title, String content, Instant publishedAt, String severity) {}
    record Received(String reportId, String status) {}
}
