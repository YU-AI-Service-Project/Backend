package dev.wattup.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
class AccountActions {
    private static final long REAUTH_SECONDS = 300;
    private final JdbcTemplate db;
    private final AuthSessions sessions;
    private final AuthEmail passwords;
    private final AccountProfile profiles;
    private final SocialProofGuard proof;
    private final boolean withdrawalEnabled;

    AccountActions(JdbcTemplate db, AuthSessions sessions, AuthEmail passwords, AccountProfile profiles,
                   SocialProofGuard proof, @Value("${wattup.account.withdrawal-enabled:false}") boolean withdrawalEnabled) {
        this.db = db;
        this.sessions = sessions;
        this.passwords = passwords;
        this.profiles = profiles;
        this.proof = proof;
        this.withdrawalEnabled = withdrawalEnabled;
    }

    @Transactional
    Reauthentication reauthenticate(Jwt access, Action action, String password, SocialIdentityVerifier.Identity identity,
                                    LocalDateTime verifiedSince) {
        long id = sessions.lockCurrent(access);
        if (identity != null) {
            proof.requireCurrent(identity.provider(), identity.subject(), verifiedSince);
        }
        boolean verified = identity == null ? passwords.verifyPassword(id, password)
                : subject(id, identity.provider()).filter(identity.subject()::equals).isPresent();
        if (!verified) {
            throw new AuthFailure(403, "REAUTHENTICATION_REQUIRED");
        }
        LocalDateTime now = AuthValues.utcNow();
        String token = AuthValues.randomToken();
        db.update("""
                INSERT INTO auth_action_tickets (ticket_hash,purpose,user_id,bound_session_family_id,issued_at,expires_at)
                VALUES (?,?,?,?,?,?)
                """, AuthValues.hash(token), action.ticketPurpose(), id, family(access), now, now.plusSeconds(REAUTH_SECONDS));
        return new Reauthentication(token, REAUTH_SECONDS, action);
    }

    @Transactional
    List<AccountProfile.SocialAccount> link(Jwt access, String raw, SocialIdentityVerifier.Identity identity,
                                           LocalDateTime verifiedSince) {
        long id = sessions.lockCurrent(access);
        Ticket ticket = ticket(access, id, Action.LINK_SOCIAL, raw);
        if (subject(id, identity.provider()).isPresent()) {
            throw new AuthFailure(409, "ALREADY_LINKED");
        }
        proof.requireCurrent(identity.provider(), identity.subject(), verifiedSince);
        discardRegistration(identity.provider(), identity.subject());
        try {
            db.update("""
                    INSERT INTO user_social_accounts (user_id,provider,provider_user_id,provider_email)
                    VALUES (?,?,?,?)
                    """, id, identity.provider(), identity.subject(), identity.email());
        } catch (DuplicateKeyException e) {
            throw new AuthFailure(409, "ACCOUNT_LINK_CONFLICT");
        }
        consume(ticket);
        return profiles.socialAccounts(id);
    }

    @Transactional(timeout = 8)
    void unlink(Jwt access, String provider, String raw) {
        long id = sessions.lockCurrent(access);
        Ticket ticket = ticket(access, id, Action.UNLINK_SOCIAL, raw);
        String subject = subject(id, provider).orElseThrow(() -> new AuthFailure(404, "SOCIAL_ACCOUNT_NOT_FOUND"));
        boolean alternative = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM user_email_credentials WHERE user_id=?)
                    OR EXISTS(SELECT 1 FROM user_social_accounts WHERE user_id=? AND provider<>?)
                """, Boolean.class, id, id, provider));
        if (!alternative) {
            throw new AuthFailure(409, "LAST_LOGIN_METHOD");
        }
        proof.disconnect(provider, subject);
        discardRegistration(provider, subject);
        db.update("DELETE FROM user_social_accounts WHERE user_id=? AND provider=?", id, provider);
        db.update("UPDATE users SET profile_image_url=NULL WHERE user_id=?", id);
        db.update("""
                UPDATE user_refresh_sessions SET revoked_at=?,revoked_reason='LOGOUT_ALL'
                WHERE user_id=? AND session_family_id<>? AND revoked_at IS NULL
                """, AuthValues.utcNow(), id, family(access));
        db.update("DELETE FROM auth_action_tickets WHERE user_id=? AND purpose LIKE 'REAUTH_%' AND ticket_id<>?",
                id, ticket.id);
        consume(ticket);
    }

    @Transactional(timeout = 8)
    void withdraw(Jwt access, String raw) {
        if (!withdrawalEnabled) {
            throw new AuthFailure(503, "WITHDRAWAL_UNAVAILABLE");
        }
        long id = sessions.lockCurrent(access);
        ticket(access, id, Action.WITHDRAW, raw);
        subject(id, "KAKAO").ifPresent(value -> proof.disconnect("KAKAO", value));
        subject(id, "GOOGLE").ifPresent(value -> proof.disconnect("GOOGLE", value));

        LocalDateTime now = AuthValues.utcNow();
        db.update("""
                UPDATE users SET account_status='WITHDRAWN',nickname='탈퇴 회원',profile_image_url=NULL,
                    withdrawn_at=?,access_tokens_valid_after=? WHERE user_id=?
                """, now, now, id);
        db.update("""
                DELETE t FROM auth_action_tickets t JOIN user_social_accounts s
                  ON t.provider=s.provider AND t.provider_user_id=s.provider_user_id
                WHERE s.user_id=? AND t.purpose='SOCIAL_SIGNUP'
                """, id);
        db.update("""
                DELETE t FROM auth_action_tickets t JOIN user_email_credentials c ON t.email=c.email
                WHERE c.user_id=? AND t.purpose IN ('EMAIL_SIGNUP','PASSWORD_RESET')
                """, id);
        db.update("""
                DELETE e FROM auth_email_challenges e JOIN user_email_credentials c ON e.email=c.email
                WHERE c.user_id=?
                """, id);
        db.update("DELETE FROM auth_action_tickets WHERE user_id=?", id);
        db.update("DELETE FROM user_refresh_sessions WHERE user_id=?", id);
        db.update("DELETE FROM user_social_accounts WHERE user_id=?", id);
        db.update("DELETE FROM user_email_credentials WHERE user_id=?", id);
        db.update("DELETE FROM user_consents WHERE user_id=?", id);
        db.update("DELETE FROM user_favorites WHERE user_id=?", id);
        db.update("DELETE FROM user_preferences WHERE user_id=?", id);
    }

    private Optional<String> subject(long id, String provider) {
        return db.query("SELECT provider_user_id FROM user_social_accounts WHERE user_id=? AND provider=?",
                (rs, i) -> rs.getString(1), id, provider).stream().findFirst();
    }

    private void discardRegistration(String provider, String subject) {
        db.update("""
                DELETE FROM auth_action_tickets WHERE purpose='SOCIAL_SIGNUP' AND provider=? AND provider_user_id=?
                """, provider, subject);
    }

    private Ticket ticket(Jwt access, long id, Action action, String raw) {
        if (!AuthValues.validToken(raw)) {
            throw new AuthFailure(403, "REAUTHENTICATION_REQUIRED");
        }
        var ticket = db.query("""
                SELECT ticket_id,expires_at,consumed_at FROM auth_action_tickets
                WHERE ticket_hash=? AND user_id=? AND bound_session_family_id=? AND purpose=? FOR UPDATE
                """, (rs, i) -> new Ticket(rs.getLong(1), rs.getObject(2, LocalDateTime.class),
                rs.getObject(3, LocalDateTime.class)), AuthValues.hash(raw), id, family(access), action.ticketPurpose())
                .stream().findFirst().orElseThrow(() -> new AuthFailure(403, "REAUTHENTICATION_REQUIRED"));
        if (!ticket.expiresAt.isAfter(AuthValues.utcNow())) {
            throw new AuthFailure(410, "REAUTHENTICATION_EXPIRED");
        }
        if (ticket.consumedAt != null) {
            throw new AuthFailure(403, "REAUTHENTICATION_REQUIRED");
        }
        return ticket;
    }

    private void consume(Ticket ticket) {
        db.update("UPDATE auth_action_tickets SET consumed_at=? WHERE ticket_id=?", AuthValues.utcNow(), ticket.id);
    }

    private static String family(Jwt access) {
        return access.getClaimAsString("sid");
    }

    enum Action {
        WITHDRAW, LINK_SOCIAL, UNLINK_SOCIAL;

        String ticketPurpose() {
            return "REAUTH_" + name();
        }
    }

    record Reauthentication(String reauthenticationToken, long expiresIn, Action purpose) {}
    private record Ticket(long id, LocalDateTime expiresAt, LocalDateTime consumedAt) {}
}
