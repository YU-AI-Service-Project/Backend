package dev.wattup.backend.operations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class InternalFailure extends RuntimeException {
    final int status;
    private final String code;

    public InternalFailure(int status, String code) {
        this.status = status;
        this.code = code;
    }

    Envelope body() {
        return new Envelope(new Error(code, "운영 요청을 처리할 수 없습니다.", List.of()), UUID.randomUUID(), Instant.now());
    }

    record Envelope(Error error, UUID requestId, Instant timestamp) {}
    record Error(String code, String message, List<Object> details) {}
}
