package dev.wattup.backend.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
class AccountProfile {
    private final JdbcTemplate db;
    private final AuthSessions sessions;

    AccountProfile(JdbcTemplate db, AuthSessions sessions) {
        this.db = db;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    UserSummary summary(Jwt access) {
        return summary(AuthSessions.userId(access));
    }

    @Transactional
    UserSummary rename(Jwt access, String nickname) {
        String normalized;
        try {
            normalized = AuthValues.nickname(nickname);
        } catch (AuthFailure e) {
            throw new AuthFailure(400, "INVALID_NICKNAME");
        }
        long id = sessions.lockCurrent(access);
        db.update("UPDATE users SET nickname = ? WHERE user_id = ?", normalized, id);
        return summary(id);
    }

    List<SocialAccount> socialAccounts(long id) {
        return db.query("""
                SELECT provider, connected_at, provider_email
                FROM user_social_accounts WHERE user_id = ? ORDER BY provider
                """, (rs, i) -> new SocialAccount(rs.getString(1), instant(rs, 2), rs.getString(3)), id);
    }

    List<AgreementDecision> agreements(Jwt access) {
        return db.query("""
                SELECT consent_type, policy_version, agreed, decided_at
                FROM user_consents WHERE user_id = ? ORDER BY decided_at DESC, consent_id DESC
                """, (rs, i) -> new AgreementDecision(rs.getString(1), rs.getString(2), rs.getBoolean(3),
                instant(rs, 4)), AuthSessions.userId(access));
    }

    @Transactional
    AgreementDecision changeAgreement(Jwt access, String type, Boolean agreed) {
        if (!"OPTIONAL_MARKETING".equals(type) || agreed == null) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
        long id = sessions.lockCurrent(access);
        LocalDateTime now = AuthValues.utcNow();
        String version = db.query("""
                SELECT policy_version FROM agreement_policies WHERE policy_type = 'OPTIONAL_MARKETING'
                  AND is_current = TRUE AND is_required = FALSE AND published_at <= ? FOR SHARE
                """, (rs, i) -> rs.getString(1), now).stream().findFirst()
                .orElseThrow(() -> new AuthFailure(404, "AGREEMENT_NOT_FOUND"));
        db.update("""
                INSERT INTO user_consents (user_id, consent_type, policy_version, agreed, decided_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, type, version, agreed, now);
        return new AgreementDecision(type, version, agreed, now.toInstant(ZoneOffset.UTC));
    }

    List<DeviceSession> sessions(Jwt access) {
        LocalDateTime now = AuthValues.utcNow();
        return db.query("""
                SELECT session_family_id, MAX(device_label), MAX(last_used_at), MIN(issued_at)
                FROM user_refresh_sessions WHERE user_id = ?
                GROUP BY session_family_id
                HAVING MAX(CASE WHEN revoked_at IS NULL AND expires_at > ? THEN 1 ELSE 0 END) = 1
                ORDER BY MIN(issued_at) DESC, session_family_id
                """, (rs, i) -> new DeviceSession(rs.getString(1), rs.getString(2), instant(rs, 3), instant(rs, 4),
                rs.getString(1).equals(access.getClaimAsString("sid"))), AuthSessions.userId(access), now);
    }

    @Transactional
    void logoutSession(Jwt access, UUID family) {
        long id = sessions.lockCurrent(access);
        int updated = db.update("""
                UPDATE user_refresh_sessions SET revoked_at = ?, revoked_reason = 'LOGOUT'
                WHERE user_id = ? AND session_family_id = ? AND revoked_at IS NULL AND expires_at > ?
                """, AuthValues.utcNow(), id, family.toString(), AuthValues.utcNow());
        if (updated == 0) {
            throw new AuthFailure(404, "SESSION_NOT_FOUND");
        }
    }

    private UserSummary summary(long id) {
        var accounts = socialAccounts(id);
        var providers = accounts.stream().map(SocialAccount::provider).toList();
        String socialEmail = accounts.stream().map(SocialAccount::email).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        return db.query("""
                SELECT u.nickname, u.profile_image_url, c.email, u.account_status, u.created_at
                FROM users u LEFT JOIN user_email_credentials c ON c.user_id = u.user_id
                WHERE u.user_id = ? AND u.account_status = 'ACTIVE'
                """, (rs, i) -> {
            String email = rs.getString(3);
            return new UserSummary(id, rs.getString(1), rs.getString(2), email == null ? socialEmail : email,
                    rs.getString(4), providers, instant(rs, 5));
        }, id).stream().findFirst().orElseThrow(() -> new AuthFailure(401, "UNAUTHORIZED"));
    }

    private static Instant instant(ResultSet row, int column) throws SQLException {
        LocalDateTime timestamp = row.getObject(column, LocalDateTime.class);
        return timestamp == null ? null : timestamp.toInstant(ZoneOffset.UTC);
    }

    record UserSummary(long userId, String nickname, String profileImageUrl, String email, String accountStatus,
                       List<String> providers, Instant createdAt) {}
    record SocialAccount(String provider, Instant connectedAt, String email) {}
    record AgreementDecision(String type, String version, boolean agreed, Instant decidedAt) {}
    record DeviceSession(String sessionId, String deviceLabel, Instant lastUsedAt, Instant createdAt,
                         boolean currentSession) {}
}
