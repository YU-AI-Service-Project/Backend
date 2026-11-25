package dev.wattup.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
class AuthEmail {
    private static final int MAX_CODE_ATTEMPTS = 5;
    private static final Duration VERIFICATION_TTL = Duration.ofMinutes(10);
    private static final Duration RESET_TTL = Duration.ofMinutes(15);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String dummyPasswordHash = passwords.encode(AuthValues.randomToken());
    private final JdbcTemplate db;
    private final AuthAccounts accounts;
    private final AuthSessions sessions;
    private final SecretKeySpec codeKey;

    AuthEmail(JdbcTemplate db, AuthAccounts accounts, AuthSessions sessions,
              @Value("${wattup.auth.email-secret:}") String secret) {
        this.db = db;
        this.accounts = accounts;
        this.sessions = sessions;
        this.codeKey = emailKey(secret);
    }

    @Transactional
    String beginVerification(String email) {
        requireCodeKey();
        boolean registered = credential(email, false).isPresent();
        LocalDateTime now = AuthValues.utcNow();
        db.update("""
                UPDATE auth_email_challenges SET status = 'EXPIRED' WHERE email = ? AND status = 'PENDING'
                """, email);
        String code = AuthValues.emailCode();
        db.update("""
                INSERT INTO auth_email_challenges (email, purpose, code_mac, issued_at, expires_at)
                VALUES (?, 'SIGNUP', ?, ?, ?)
                """, email, mac(email, code), now, now.plus(VERIFICATION_TTL));
        return registered ? null : code;
    }

    @Transactional(noRollbackFor = AuthFailure.class)
    Verification confirmVerification(String email, String code) {
        requireCodeKey();
        if (code == null || !code.matches("[0-9]{6}")) {
            throw new AuthFailure(400, "INVALID_VERIFICATION_CODE");
        }
        var challenge = db.query("""
                SELECT challenge_id, code_mac, status, attempt_count, expires_at
                FROM auth_email_challenges WHERE email = ? AND purpose = 'SIGNUP'
                ORDER BY issued_at DESC, challenge_id DESC LIMIT 1 FOR UPDATE
                """, (rs, i) -> new Challenge(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getInt(4), rs.getObject(5, LocalDateTime.class)), email).stream().findFirst()
                .orElseThrow(() -> new AuthFailure(400, "INVALID_VERIFICATION_CODE"));
        LocalDateTime now = AuthValues.utcNow();
        if (!challenge.expiresAt.isAfter(now) || "EXPIRED".equals(challenge.status)) {
            throw new AuthFailure(410, "VERIFICATION_EXPIRED");
        }
        if ("LOCKED".equals(challenge.status)) {
            throw new AuthFailure(429, "TOO_MANY_ATTEMPTS");
        }
        if (!"PENDING".equals(challenge.status)) {
            throw new AuthFailure(410, "VERIFICATION_EXPIRED");
        }
        if (!constantTimeEquals(challenge.codeMac, mac(email, code)) || credential(email, false).isPresent()) {
            int attempts = challenge.attemptCount + 1;
            boolean locked = attempts >= MAX_CODE_ATTEMPTS;
            db.update("UPDATE auth_email_challenges SET attempt_count = ?, status = ? WHERE challenge_id = ?",
                    attempts, locked ? "LOCKED" : "PENDING", challenge.id);
            throw new AuthFailure(locked ? 429 : 400, locked ? "TOO_MANY_ATTEMPTS" : "INVALID_VERIFICATION_CODE");
        }
        String token = accounts.ticket("EMAIL_SIGNUP", null, email, null, null, null, VERIFICATION_TTL);
        db.update("UPDATE auth_email_challenges SET status = 'VERIFIED', verified_at = ? WHERE challenge_id = ?", now,
                challenge.id);
        return new Verification(token, VERIFICATION_TTL.toSeconds());
    }

    @Transactional
    AuthAccounts.Created signup(String email, String password, String token, String nickname,
                                List<AuthAccounts.Consent> consents) {
        AuthValues.validatePassword(password);
        var ticket = accounts.requireTicket(token, "EMAIL_SIGNUP", "VERIFICATION_EXPIRED", true);
        if (!email.equals(ticket.email())) {
            throw new AuthFailure(410, "VERIFICATION_EXPIRED");
        }
        long id = accounts.createUser(nickname, null, consents);
        try {
            db.update("""
                    INSERT INTO user_email_credentials (user_id, email, password_hash, email_verified_at)
                    VALUES (?, ?, ?, ?)
                    """, id, email, passwords.encode(password), AuthValues.utcNow());
        } catch (DuplicateKeyException e) {
            throw new AuthFailure(409, "EMAIL_ALREADY_EXISTS");
        }
        accounts.consume(ticket);
        return new AuthAccounts.Created(accounts.user(id), sessions.start(id, null));
    }

    @Transactional
    AuthAccounts.Login login(String email, String password) {
        var hit = credential(email, false);
        if (hit.isEmpty()) {
            matches(password, dummyPasswordHash);
            throw new AuthFailure(401, "INVALID_CREDENTIALS");
        }
        long id = hit.get().userId;
        boolean active = sessions.activeAndLock(id);
        var latest = credential(email, true);
        if (latest.isEmpty() || latest.get().userId != id || !matches(password, latest.get().passwordHash)) {
            throw new AuthFailure(401, "INVALID_CREDENTIALS");
        }
        if (!active) {
            throw new AuthFailure(403, "ACCOUNT_SUSPENDED");
        }
        return accounts.login(id);
    }

    @Transactional
    String beginReset(String email) {
        var first = credential(email, false);
        if (first.isEmpty() || !sessions.activeAndLock(first.get().userId)) {
            return null;
        }
        var latest = credential(email, true);
        if (latest.isEmpty() || latest.get().userId != first.get().userId) {
            return null;
        }
        long userId = latest.get().userId;
        return accounts.ticket("PASSWORD_RESET", userId, email, null, null, null, RESET_TTL);
    }

    @Transactional
    void reset(String raw, String newPassword) {
        AuthValues.validatePassword(newPassword);
        var initial = accounts.findTicket(raw, "PASSWORD_RESET", false)
                .orElseThrow(() -> new AuthFailure(401, "INVALID_RESET_TOKEN"));
        sessions.requireActiveAndLock(initial.userId());
        var ticket = accounts.requireTicket(raw, "PASSWORD_RESET", "RESET_TOKEN_EXPIRED", true);
        updatePassword(ticket.userId(), newPassword);
        retireResetTickets(ticket.userId());
    }

    @Transactional
    void change(Jwt access, String currentPassword, String newPassword) {
        AuthValues.validatePassword(newPassword);
        long userId = sessions.lockCurrent(access);
        String hash = db.query("SELECT password_hash FROM user_email_credentials WHERE user_id = ? FOR UPDATE",
                (rs, i) -> rs.getString(1), userId).stream().findFirst()
                .orElseThrow(() -> new AuthFailure(422, "EMAIL_PASSWORD_NOT_ENABLED"));
        if (!matches(currentPassword, hash)) {
            throw new AuthFailure(401, "INVALID_CREDENTIALS");
        }
        updatePassword(userId, newPassword);
        retireResetTickets(userId);
    }

    boolean verifyPassword(long userId, String password) {
        return db.query("SELECT password_hash FROM user_email_credentials WHERE user_id = ? FOR UPDATE",
                (rs, i) -> rs.getString(1), userId).stream().findFirst().filter(hash -> matches(password, hash)).isPresent();
    }

    private Optional<Credential> credential(String email, boolean lock) {
        return db.query("SELECT user_id, password_hash FROM user_email_credentials WHERE email = ?"
                + (lock ? " FOR UPDATE" : ""), (rs, i) -> new Credential(rs.getLong(1), rs.getString(2)), email)
                .stream().findFirst();
    }

    private void updatePassword(long userId, String newPassword) {
        if (db.update("UPDATE user_email_credentials SET password_hash = ?, password_changed_at = ? WHERE user_id = ?",
                passwords.encode(newPassword), AuthValues.utcNow(), userId) != 1) {
            throw new AuthFailure(401, "INVALID_RESET_TOKEN");
        }
        sessions.revoke(userId, null, "PASSWORD_CHANGED");
    }

    private void retireResetTickets(long userId) {
        db.update("""
                UPDATE auth_action_tickets SET consumed_at = ?
                WHERE user_id = ? AND purpose = 'PASSWORD_RESET' AND consumed_at IS NULL
                """, AuthValues.utcNow(), userId);
    }

    private boolean matches(String candidate, String hash) {
        return candidate != null && candidate.length() <= 72
                && candidate.getBytes(StandardCharsets.UTF_8).length <= 72 && passwords.matches(candidate, hash);
    }

    private void requireCodeKey() {
        if (codeKey == null) {
            throw new AuthFailure(503, "AUTH_UNAVAILABLE");
        }
    }

    private String mac(String email, String code) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(codeKey);
            return HexFormat.of().formatHex(mac.doFinal((email + "|" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SecretKeySpec emailKey(String secret) {
        if (secret.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("WattUp email key must be base64");
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("WattUp email key must contain at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    record Verification(String verificationToken, long expiresIn) {}
    private record Credential(long userId, String passwordHash) {}
    private record Challenge(long id, String codeMac, String status, int attemptCount, LocalDateTime expiresAt) {}
}
