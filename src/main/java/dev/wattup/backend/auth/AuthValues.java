package dev.wattup.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

final class AuthValues {
    private static final Pattern EMAIL = Pattern.compile(
            "[a-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
                    + "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthValues() {
    }

    static LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String emailCode() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    static boolean validToken(String value) {
        return value != null && value.length() == 43 && value.matches("[A-Za-z0-9_-]+");
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String email(String input) {
        String email = input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
        String local = email.substring(0, Math.max(0, email.indexOf('@')));
        if (email.length() > 254 || !EMAIL.matcher(email).matches() || local.startsWith(".")
                || local.endsWith(".") || local.contains("..")) {
            throw new AuthFailure(400, "INVALID_EMAIL");
        }
        return email;
    }

    static String nickname(String input) {
        String nickname = input == null ? "" : input.strip();
        if (nickname.isEmpty() || nickname.codePointCount(0, nickname.length()) > 60
                || !nickname.matches("[\\p{L}\\p{M}\\p{N} _-]+")) {
            throw new AuthFailure(400, "INVALID_REQUEST");
        }
        return nickname;
    }

    static void validatePassword(String value) {
        if (value == null || value.length() > 72 || value.codePointCount(0, value.length()) < 12
                || value.getBytes(StandardCharsets.UTF_8).length > 72 || value.indexOf(0) >= 0) {
            throw new AuthFailure(400, "INVALID_PASSWORD");
        }
    }
}
