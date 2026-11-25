package dev.wattup.backend.auth;

import dev.wattup.backend.station.ChargerProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
class SearchPreferences {
    private static final Set<String> FIELDS = Set.of("defaultRadiusM", "defaultProfile", "minOutputKw",
            "defaultChargerTypeCodes");
    private static final BigDecimal MAX_OUTPUT = new BigDecimal("999999.99");
    private static final TypeReference<List<String>> TYPES = new TypeReference<>() {};
    private static final Preference DEFAULTS = new Preference(3000, ChargerProfile.ALL, null, List.of(), null);
    private final JdbcTemplate db;
    private final AuthSessions sessions;
    private final JsonMapper json;

    SearchPreferences(JdbcTemplate db, AuthSessions sessions, JsonMapper json) {
        this.db = db;
        this.sessions = sessions;
        this.json = json;
    }

    Preference get(Jwt access) {
        return load(AuthSessions.userId(access));
    }

    @Transactional
    Preference patch(Jwt access, JsonNode body) {
        if (!body.isObject() || body.isEmpty() || body.properties().stream().anyMatch(field -> !FIELDS.contains(field.getKey()))) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
        long id = sessions.lockCurrent(access);
        var prior = load(id);
        int radius = body.has("defaultRadiusM") ? radius(body.path("defaultRadiusM")) : prior.defaultRadiusM;
        ChargerProfile profile = body.has("defaultProfile") ? profile(body.path("defaultProfile")) : prior.defaultProfile;
        BigDecimal minOutput = body.has("minOutputKw") ? output(body.path("minOutputKw")) : prior.minOutputKw;
        List<String> types = body.has("defaultChargerTypeCodes") ? types(body.path("defaultChargerTypeCodes"))
                : prior.defaultChargerTypeCodes;
        if (types.stream().anyMatch(code -> !profile.includes(code))) {
            throw new AuthFailure(422, "UNSUPPORTED_CHARGER_FILTER");
        }
        LocalDateTime now = AuthValues.utcNow().truncatedTo(ChronoUnit.MILLIS);
        db.update("""
                INSERT INTO user_preferences
                    (user_id, default_radius_m, default_profile, min_output_kw, default_charger_type_codes, updated_at)
                VALUES (?,?,?,?,?,?) AS incoming
                ON DUPLICATE KEY UPDATE default_radius_m=incoming.default_radius_m,
                    default_profile=incoming.default_profile, min_output_kw=incoming.min_output_kw,
                    default_charger_type_codes=incoming.default_charger_type_codes, updated_at=incoming.updated_at
                """, id, radius, profile.name(), minOutput, types.isEmpty() ? null : json.writeValueAsString(types), now);
        return new Preference(radius, profile, minOutput, types, now.toInstant(ZoneOffset.UTC));
    }

    private Preference load(long id) {
        return db.query("""
                SELECT default_radius_m, default_profile, min_output_kw, default_charger_type_codes, updated_at
                FROM user_preferences WHERE user_id=?
                """, (rs, i) -> {
            String types = rs.getString(4);
            return new Preference(rs.getInt(1), ChargerProfile.valueOf(rs.getString(2)), rs.getBigDecimal(3),
                    types == null ? List.of() : json.readValue(types, TYPES),
                    rs.getObject(5, LocalDateTime.class).toInstant(ZoneOffset.UTC));
        }, id).stream().findFirst().orElse(DEFAULTS);
    }

    private static int radius(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 100 || value.asInt() > 10_000) {
            throw new AuthFailure(400, "INVALID_RADIUS");
        }
        return value.asInt();
    }

    private static ChargerProfile profile(JsonNode value) {
        if (value.isString()) {
            try {
                return ChargerProfile.valueOf(value.asText());
            } catch (IllegalArgumentException ignored) {
            }
        }
        throw new AuthFailure(422, "UNSUPPORTED_PROFILE");
    }

    private static BigDecimal output(JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            try {
                BigDecimal decimal = value.decimalValue();
                if (decimal.scale() >= -6 && decimal.scale() <= 18 && decimal.signum() >= 0
                        && decimal.compareTo(MAX_OUTPUT) <= 0 && decimal.stripTrailingZeros().scale() <= 2) {
                    return decimal.setScale(2);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        throw new AuthFailure(400, "INVALID_REQUEST");
    }

    private static List<String> types(JsonNode value) {
        if (value.isNull()) {
            return List.of();
        }
        if (!value.isArray() || value.size() > 20) {
            throw new AuthFailure(422, "UNSUPPORTED_CHARGER_FILTER");
        }
        var codes = new LinkedHashSet<String>();
        for (var node : value) {
            if (!node.isString() || !node.asText().matches("[0-9]{2}")) {
                throw new AuthFailure(422, "UNSUPPORTED_CHARGER_FILTER");
            }
            codes.add(node.asText());
        }
        return List.copyOf(codes);
    }

    record Preference(int defaultRadiusM, ChargerProfile defaultProfile, BigDecimal minOutputKw,
                      List<String> defaultChargerTypeCodes, Instant updatedAt) {}
}
