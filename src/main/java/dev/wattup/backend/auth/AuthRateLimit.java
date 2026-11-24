package dev.wattup.backend.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
class AuthRateLimit {
    private final JdbcTemplate db;

    AuthRateLimit(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = AuthFailure.class)
    void hit(String scope, String client, int limit, Duration window, String code) {
        String key = AuthValues.hash(scope + "|" + client);
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
}
