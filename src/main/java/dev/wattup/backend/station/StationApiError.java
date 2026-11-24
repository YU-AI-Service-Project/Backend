package dev.wattup.backend.station;

import org.springframework.http.HttpStatus;

public final class StationApiError extends RuntimeException {
    final HttpStatus status;
    final String code;
    final String field;
    final String reason;

    private StationApiError(HttpStatus status, String code, String message, String field, String reason) {
        super(message);
        this.status = status;
        this.code = code;
        this.field = field;
        this.reason = reason;
    }

    public static StationApiError invalid(String code, String field) {
        return new StationApiError(HttpStatus.BAD_REQUEST, code, "요청값을 확인해 주세요.", field, "OUT_OF_RANGE");
    }

    public static StationApiError binding(String code, String field, String reason) {
        return new StationApiError(HttpStatus.BAD_REQUEST, code, "요청값을 확인해 주세요.", field, reason);
    }

    public static StationApiError unsupported(String code, String field) {
        return new StationApiError(HttpStatus.UNPROCESSABLE_ENTITY, code, "지원하는 충전기 조건을 확인해 주세요.",
                field, "UNSUPPORTED");
    }

    public static StationApiError notFound() {
        return new StationApiError(HttpStatus.NOT_FOUND, "STATION_NOT_FOUND", "충전소를 찾을 수 없습니다.", null, null);
    }

    static StationApiError unavailable() {
        return new StationApiError(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE",
                "충전소 정보를 조회할 수 없습니다. 잠시 후 다시 시도해 주세요.", null, null);
    }

    public static StationApiError modelUnavailable() {
        return new StationApiError(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_UNAVAILABLE",
                "충전 가능성을 예측할 수 없습니다. 잠시 후 다시 시도해 주세요.", null, null);
    }
}
