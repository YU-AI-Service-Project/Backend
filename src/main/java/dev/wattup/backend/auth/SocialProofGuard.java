package dev.wattup.backend.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
class SocialProofGuard {
    private final JdbcTemplate db;
    private final KakaoDisconnect kakao;

    SocialProofGuard(JdbcTemplate db, KakaoDisconnect kakao) {
        this.db = db;
        this.kakao = kakao;
    }

    LocalDateTime now() {
        return db.queryForObject("SELECT UTC_TIMESTAMP(3)", LocalDateTime.class);
    }

    void requireCurrent(String provider, String subject, LocalDateTime startedAt) {
        int slot = slot(provider, subject);
        lock(slot);
        LocalDateTime disconnected = db.queryForObject(
                "SELECT disconnected_at FROM auth_social_proof_slots WHERE slot_id=? FOR UPDATE",
                LocalDateTime.class, slot);
        if (disconnected != null && !startedAt.isAfter(disconnected)) {
            throw new AuthFailure(401, "INVALID_PROVIDER_TOKEN");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 7)
    void disconnect(String provider, String subject) {
        int slot = slot(provider, subject);
        lock(slot);
        if ("KAKAO".equals(provider)) {
            kakao.unlink(subject);
        }
        db.update("UPDATE auth_social_proof_slots SET disconnected_at=UTC_TIMESTAMP(3) WHERE slot_id=?", slot);
    }

    private void lock(int slot) {
        db.update("""
                INSERT INTO auth_social_proof_slots (slot_id) VALUES (?)
                ON DUPLICATE KEY UPDATE slot_id=slot_id
                """, slot);
    }

    private static int slot(String provider, String subject) {
        return Integer.parseInt(AuthValues.hash(provider + "|" + subject).substring(0, 2), 16);
    }
}
