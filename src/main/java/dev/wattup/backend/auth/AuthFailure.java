package dev.wattup.backend.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class AuthFailure extends RuntimeException {
    final int status;
    final String code;

    AuthFailure(int status, String code) {
        this.status = status;
        this.code = code;
    }

    Envelope response() {
        String message = switch (status) {
            case 401 -> "인증 정보를 확인해 주세요.";
            case 403 -> "이 요청에 필요한 조건을 확인해 주세요.";
            case 429 -> "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.";
            case 502, 503 -> "인증 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.";
            default -> "요청 정보를 확인해 주세요.";
        };
        return new Envelope(new ErrorBody(code, message, List.of()), UUID.randomUUID(), Instant.now());
    }

    record Envelope(ErrorBody error, UUID requestId, Instant timestamp) {
    }

    record ErrorBody(String code, String message, List<Object> details) {
    }
}
