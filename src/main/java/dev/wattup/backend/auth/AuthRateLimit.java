package dev.wattup.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

@Component
class AuthRateLimit {
    private final JdbcTemplate db;
    private final SecretKeySpec bucketKey;

    AuthRateLimit(JdbcTemplate db, @Value("${wattup.auth.jwt-secret:}") String jwt,
                  @Value("${wattup.auth.email-secret:}") String email) {
        this.db = db;
        String configured = jwt.isBlank() ? email : jwt;
        byte[] master = configured.isBlank() ? AuthValues.randomToken().getBytes(StandardCharsets.UTF_8)
                : Base64.getDecoder().decode(configured);
        this.bucketKey = new SecretKeySpec(mac(new SecretKeySpec(master, "HmacSHA256"),
                "wattup/auth/request-bucket/v1"), "HmacSHA256");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = AuthFailure.class)
    void hit(String scope, String client, int limit, Duration window, String code) {
        String key = key(scope, client);
        LocalDateTime now = AuthValues.utcNow();
        LocalDateTime cutoff = now.minus(window);
        db.update("""
                INSERT INTO auth_request_buckets (bucket_key, window_started_at, hit_count) VALUES (?, ?, 1)
                ON DUPLICATE KEY UPDATE
                  hit_count = IF(window_started_at <= ?, 1, LEAST(hit_count + 1, ?)),
                  window_started_at = IF(window_started_at <= ?, ?, window_started_at)
                """, key, now, cutoff, limit + 1, cutoff, now);
        int count = db.queryForObject("SELECT hit_count FROM auth_request_buckets WHERE bucket_key = ?", Integer.class, key);
        if (count > limit) {
            throw new AuthFailure(429, code);
        }
    }

    @Scheduled(initialDelay = 15, fixedDelay = 15, timeUnit = TimeUnit.MINUTES)
    @Transactional(timeout = 3)
    void removeExpired() {
        db.update("""
                DELETE FROM auth_request_buckets WHERE window_started_at < ?
                ORDER BY window_started_at LIMIT 5000
                """, AuthValues.utcNow().minusHours(2));
    }

    String key(String scope, String client) {
        return HexFormat.of().formatHex(mac(bucketKey, scope + "|" + client));
    }

    private static byte[] mac(SecretKeySpec key, String value) {
        try {
            Mac digest = Mac.getInstance("HmacSHA256");
            digest.init(key);
            return digest.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not derive authentication rate-limit key", e);
        }
    }
}
