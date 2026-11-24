package dev.wattup.backend.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
class AuthAccounts {
    static final List<String> REQUIRED_TYPES = List.of("TERMS", "PRIVACY");
    static final long SIGNUP_TTL = 600;
    private final JdbcTemplate db;
    private final AuthSessions sessions;
    private final JsonMapper json;
    private final SocialProofGuard proof;

    AuthAccounts(JdbcTemplate db, AuthSessions sessions, JsonMapper json, SocialProofGuard proof) {
        this.db = db;
        this.sessions = sessions;
        this.json = json;
        this.proof = proof;
    }

    List<Agreement> agreements() {
        return policies(false);
    }

    @Transactional
    SocialResult social(SocialIdentityVerifier.Identity identity, LocalDateTime verifiedSince) {
        var userId = db.query("""
                SELECT user_id FROM user_social_accounts WHERE provider = ? AND provider_user_id = ?
                """, (rs, i) -> rs.getLong(1), identity.provider(), identity.subject()).stream().findFirst();
        if (userId.isPresent()) {
            sessions.requireActiveAndLock(userId.get());
        }
        proof.requireCurrent(identity.provider(), identity.subject(), verifiedSince);
        var stillLinked = db.query("""
                SELECT user_id FROM user_social_accounts WHERE provider = ? AND provider_user_id = ? FOR UPDATE
                """, (rs, i) -> rs.getLong(1), identity.provider(), identity.subject()).stream().findFirst();
        if (!userId.equals(stillLinked)) {
            throw new AuthFailure(409, "ACCOUNT_LINK_CONFLICT");
        }
        if (userId.isPresent()) {
            return new SocialResult(login(userId.get()), null);
        }
        String picture = httpsPicture(identity.profileImageUrl());
        String ticket = ticket("SOCIAL_SIGNUP", null, identity.email(), identity.provider(), identity.subject(), picture,
                Duration.ofSeconds(SIGNUP_TTL));
        return new SocialResult(null, new Registration(true, ticket, SIGNUP_TTL,
                new SuggestedProfile(identity.nickname(), picture), REQUIRED_TYPES));
    }

    @Transactional
    Created socialSignup(String token, String nickname, List<Consent> consents) {
        Ticket candidate = requireTicket(token, "SOCIAL_SIGNUP", "REGISTRATION_EXPIRED", false);
        proof.requireCurrent(candidate.provider, candidate.providerSubject, candidate.issuedAt);
        Ticket ticket = requireTicket(token, "SOCIAL_SIGNUP", "REGISTRATION_EXPIRED", true);
        long user = createUser(nickname, ticket.picture, consents);
        try {
            db.update("""
                    INSERT INTO user_social_accounts (user_id, provider, provider_user_id, provider_email)
                    VALUES (?, ?, ?, ?)
                    """, user, ticket.provider, ticket.providerSubject, ticket.email);
        } catch (DuplicateKeyException e) {
            throw new AuthFailure(409, "ACCOUNT_ALREADY_LINKED");
        }
        consume(ticket);
        return new Created(user(user), sessions.start(user, null));
    }

    long createUser(String nickname, String picture, List<Consent> list) {
        String name = AuthValues.nickname(nickname);
        validateConsents(list);
        var key = new GeneratedKeyHolder();
        db.update(connection -> {
            var sql = connection.prepareStatement("INSERT INTO users (nickname, profile_image_url) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            sql.setString(1, name);
            sql.setString(2, picture);
            return sql;
        }, key);
        long id = Objects.requireNonNull(key.getKey()).longValue();
        for (var consent : list) {
            db.update("""
                    INSERT INTO user_consents (user_id, consent_type, policy_version, agreed, decided_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, consent.type, consent.version, consent.agreed, AuthValues.utcNow());
        }
        return id;
    }

    private void validateConsents(List<Consent> submitted) {
        if (submitted == null || submitted.size() > 3) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
        var policies = policies(true);
        if (!policies.stream().filter(Agreement::required).map(Agreement::type).toList().containsAll(REQUIRED_TYPES)) {
            throw new AuthFailure(503, "AUTH_UNAVAILABLE");
        }
        var seen = new HashSet<String>();
        for (var consent : submitted) {
            if (consent == null || consent.type == null || consent.version == null
                    || consent.agreed == null || !seen.add(consent.type)) {
                throw new AuthFailure(400, "INVALID_REQUEST");
            }
            if (policies.stream().noneMatch(p -> p.type.equals(consent.type) && p.version.equals(consent.version))) {
                throw new AuthFailure(403, "CONSENT_REQUIRED");
            }
        }
        if (policies.stream().filter(Agreement::required).anyMatch(p -> submitted.stream()
                .noneMatch(consent -> consent.type.equals(p.type) && consent.agreed))) {
            throw new AuthFailure(403, "CONSENT_REQUIRED");
        }
    }

    private List<Agreement> policies(boolean lock) {
        return db.query("""
                SELECT policy_type, policy_version, is_required, title, document_url
                FROM agreement_policies WHERE is_current = TRUE AND published_at <= ? ORDER BY policy_type
                """ + (lock ? " FOR SHARE" : ""), (rs, i) -> new Agreement(rs.getString(1), rs.getString(2),
                rs.getBoolean(3), rs.getString(4), rs.getString(5)), AuthValues.utcNow());
    }

    Login login(long id) {
        return new Login(false, user(id), sessions.start(id, null));
    }

    User user(long id) {
        var providers = db.queryForList("SELECT provider FROM user_social_accounts WHERE user_id = ? ORDER BY provider",
                String.class, id);
        return db.queryForObject("SELECT nickname, account_status FROM users WHERE user_id = ?",
                (rs, i) -> new User(id, rs.getString(1), providers, rs.getString(2)), id);
    }

    String ticket(String purpose, Long user, String email, String provider, String subject, String picture, Duration ttl) {
        String token = AuthValues.randomToken();
        LocalDateTime now = proof.now();
        db.update("""
                INSERT INTO auth_action_tickets (ticket_hash, purpose, user_id, email, provider, provider_user_id,
                    context_json, issued_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, AuthValues.hash(token), purpose, user, email, provider, subject,
                picture == null ? null : json.writeValueAsString(picture), now, now.plus(ttl));
        return token;
    }

    Ticket requireTicket(String token, String purpose, String expiredCode, boolean lock) {
        Ticket ticket = findTicket(token, purpose, lock).orElseThrow(() -> new AuthFailure(410, expiredCode));
        if (ticket.consumedAt != null || !ticket.expiresAt.isAfter(AuthValues.utcNow())) {
            throw new AuthFailure(410, expiredCode);
        }
        return ticket;
    }

    Optional<Ticket> findTicket(String token, String purpose, boolean lock) {
        if (!AuthValues.validToken(token)) {
            return Optional.empty();
        }
        return db.query("""
                SELECT ticket_id, user_id, email, provider, provider_user_id,
                    JSON_UNQUOTE(context_json), expires_at, consumed_at, issued_at
                FROM auth_action_tickets WHERE ticket_hash = ? AND purpose = ?
                """ + (lock ? " FOR UPDATE" : ""), (rs, i) -> new Ticket(rs.getLong(1), rs.getObject(2, Long.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getObject(7, LocalDateTime.class), rs.getObject(8, LocalDateTime.class), rs.getObject(9, LocalDateTime.class)),
                AuthValues.hash(token), purpose).stream().findFirst();
    }

    void consume(Ticket ticket) {
        db.update("UPDATE auth_action_tickets SET consumed_at = ? WHERE ticket_id = ?", AuthValues.utcNow(), ticket.id);
    }

    private static String httpsPicture(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    ? url : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    record Ticket(long id, Long userId, String email, String provider, String providerSubject, String picture,
                  LocalDateTime expiresAt, LocalDateTime consumedAt, LocalDateTime issuedAt) {}
    record Agreement(String type, String version, boolean required, String title, String documentUrl) {}
    record Consent(String type, String version, Boolean agreed) {}
    record User(long userId, String nickname, List<String> providers, String accountStatus) {}
    record Login(boolean isNewUser, User user, AuthSessions.Tokens tokens) {}
    record Created(User user, AuthSessions.Tokens tokens) {}
    record Registration(boolean isNewUser, String registrationToken, long registrationExpiresIn,
                        SuggestedProfile suggestedProfile, List<String> requiredAgreementTypes) {}
    record SuggestedProfile(String nickname, String profileImageUrl) {}
    record SocialResult(Login login, Registration registration) {}
}
