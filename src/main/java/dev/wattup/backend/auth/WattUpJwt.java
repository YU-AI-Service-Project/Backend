package dev.wattup.backend.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
final class WattUpJwt {
    static final long LIFETIME_SECONDS = 3600;
    private static final String ISSUER = "wattup-backend";
    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;

    WattUpJwt(@Value("${wattup.auth.jwt-secret:}") String secret) {
        if (secret.isBlank()) {
            encoder = null;
            decoder = null;
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("WattUp JWT key must be base64");
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("WattUp JWT key must contain at least 32 bytes");
        }
        var key = new SecretKeySpec(bytes, "HmacSHA256");
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
    }

    void requireConfigured() {
        if (encoder == null) {
            throw new AuthFailure(503, "AUTH_UNAVAILABLE");
        }
    }

    String issue(long userId, String family) {
        requireConfigured();
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(ISSUER).subject(Long.toString(userId))
                .issuedAt(now).expiresAt(now.plusSeconds(LIFETIME_SECONDS))
                .id(UUID.randomUUID().toString()).claim("sid", family).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    Jwt decode(String token) {
        if (decoder == null || token.length() > 4096) {
            throw new BadJwtException("Invalid WattUp token");
        }
        Jwt jwt = decoder.decode(token);
        if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(Instant.now())
                || jwt.getSubject() == null || !jwt.getSubject().matches("[1-9][0-9]{0,18}")
                || jwt.getClaimAsString("sid") == null) {
            throw new BadJwtException("Invalid WattUp claims");
        }
        return jwt;
    }
}
