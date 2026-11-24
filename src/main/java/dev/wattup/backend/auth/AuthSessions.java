package dev.wattup.backend.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
class AuthSessions {
    private final JdbcTemplate db;
    private final WattUpJwt jwt;

    AuthSessions(JdbcTemplate db, WattUpJwt jwt) {
        this.db = db;
        this.jwt = jwt;
    }

    Jwt authenticate(String token) {
        Jwt access = jwt.decode(token);
        long userId;
        try {
            userId = Long.parseLong(access.getSubject());
        } catch (NumberFormatException e) {
            throw new BadJwtException("Invalid WattUp subject");
        }
        if (!Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM user_refresh_sessions s JOIN users u ON u.user_id = s.user_id
                  WHERE s.user_id = ? AND s.session_family_id = ? AND s.revoked_at IS NULL AND s.expires_at > ?
                    AND u.account_status = 'ACTIVE')
                """, Boolean.class, userId, access.getClaimAsString("sid"), AuthValues.utcNow()))) {
            throw new BadJwtException("WattUp session unavailable");
        }
        return access;
    }

    Tokens start(long userId, String deviceLabel) {
        return issue(userId, UUID.randomUUID().toString(), deviceLabel, AuthValues.utcNow());
    }

    @Transactional(noRollbackFor = AuthFailure.class)
    Tokens refresh(String raw) {
        jwt.requireConfigured();
        String hash = tokenHash(raw);
        long userId = db.query("SELECT user_id FROM user_refresh_sessions WHERE refresh_token_hash = ?",
                (rs, i) -> rs.getLong(1), hash).stream().findFirst()
                .orElseThrow(() -> new AuthFailure(401, "REFRESH_TOKEN_INVALID"));
        if (!activeAndLock(userId)) {
            throw new AuthFailure(401, "SESSION_REVOKED");
        }
        Session row = session(hash);
        LocalDateTime now = AuthValues.utcNow();
        if (!row.expiresAt.isAfter(now)) {
            throw new AuthFailure(401, "REFRESH_TOKEN_EXPIRED");
        }
        if (row.reason != null) {
            if ("ROTATED".equals(row.reason)) {
                revoke(userId, row.family, "REPLAY_DETECTED");
            }
            throw new AuthFailure(401, "SESSION_REVOKED");
        }
        db.update("""
                UPDATE user_refresh_sessions SET revoked_at = ?, revoked_reason = 'ROTATED', last_used_at = ?
                WHERE refresh_token_hash = ?
                """, now, now, hash);
        return issue(userId, row.family, row.deviceLabel, now);
    }

    @Transactional
    void logout(Jwt access, String raw) {
        long userId = userId(access);
        requireActiveAndLock(userId);
        var row = session(tokenHash(raw));
        if (row.userId != userId || !row.family.equals(access.getClaimAsString("sid"))) {
            throw new AuthFailure(401, "REFRESH_TOKEN_INVALID");
        }
        revoke(userId, row.family, "LOGOUT");
    }

    @Transactional
    void logoutAll(Jwt access) {
        long userId = userId(access);
        requireActiveAndLock(userId);
        revoke(userId, null, "LOGOUT_ALL");
    }

    void requireActiveAndLock(long userId) {
        if (!activeAndLock(userId)) {
            throw new AuthFailure(403, "ACCOUNT_SUSPENDED");
        }
    }

    boolean activeAndLock(long userId) {
        List<String> states = db.query("SELECT account_status FROM users WHERE user_id = ? FOR UPDATE",
                (rs, i) -> rs.getString(1), userId);
        return !states.isEmpty() && "ACTIVE".equals(states.getFirst());
    }

    void revoke(long userId, String family, String reason) {
        if (family == null) {
            db.update("""
                    UPDATE user_refresh_sessions SET revoked_at = ?, revoked_reason = ?
                    WHERE user_id = ? AND revoked_at IS NULL
                    """, AuthValues.utcNow(), reason, userId);
        } else {
            db.update("""
                    UPDATE user_refresh_sessions SET revoked_at = ?, revoked_reason = ?
                    WHERE user_id = ? AND session_family_id = ? AND revoked_at IS NULL
                    """, AuthValues.utcNow(), reason, userId, family);
        }
    }

    static long userId(Jwt access) {
        return Long.parseLong(access.getSubject());
    }

    private Tokens issue(long userId, String family, String label, LocalDateTime now) {
        String access = jwt.issue(userId, family);
        String refresh = AuthValues.randomToken();
        db.update("""
                INSERT INTO user_refresh_sessions
                  (user_id, session_family_id, device_label, refresh_token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId, family, label, AuthValues.hash(refresh), now, now.plusDays(30));
        return new Tokens(access, refresh, "Bearer", WattUpJwt.LIFETIME_SECONDS);
    }

    private Session session(String hash) {
        return db.query("""
                SELECT user_id, session_family_id, device_label, expires_at, revoked_reason
                FROM user_refresh_sessions WHERE refresh_token_hash = ? FOR UPDATE
                """, (rs, i) -> new Session(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, LocalDateTime.class), rs.getString(5)), hash).stream().findFirst()
                .orElseThrow(() -> new AuthFailure(401, "REFRESH_TOKEN_INVALID"));
    }

    private static String tokenHash(String raw) {
        if (!AuthValues.validToken(raw)) {
            throw new AuthFailure(401, "REFRESH_TOKEN_INVALID");
        }
        return AuthValues.hash(raw);
    }

    record Tokens(String accessToken, String refreshToken, String tokenType, long expiresIn) {
    }

    private record Session(long userId, String family, String deviceLabel, LocalDateTime expiresAt, String reason) {
    }
}
