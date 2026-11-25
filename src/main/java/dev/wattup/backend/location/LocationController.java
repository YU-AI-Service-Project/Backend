package dev.wattup.backend.location;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/locations")
class LocationController {
    private final KakaoLocalClient kakao;

    LocationController(KakaoLocalClient kakao) {
        this.kakao = kakao;
    }

    @GetMapping("/search")
    SearchResult search(@RequestParam(required = false) String query, @RequestParam(required = false) String limit) {
        String keyword = query == null ? "" : query.strip();
        if (keyword.isEmpty()) {
            throw new InvalidInput("INVALID_REQUEST", "query", "REQUIRED");
        }
        if (keyword.codePointCount(0, keyword.length()) > 100 || keyword.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidInput("INVALID_REQUEST", "query", "INVALID_FORMAT");
        }
        return new SearchResult(keyword, kakao.search(keyword, limit(limit)));
    }

    @GetMapping("/reverse-geocode")
    ReverseResult reverse(@RequestParam(required = false) String latitude, @RequestParam(required = false) String longitude) {
        double lat = coordinate(latitude, "latitude", 90);
        double lon = coordinate(longitude, "longitude", 180);
        var address = kakao.reverse(lat, lon);
        return new ReverseResult(lat, lon, address.road(), address.jibun(), address.displayName());
    }

    private static int limit(String input) {
        if (input == null) {
            return 5;
        }
        try {
            int value = Integer.parseInt(input);
            if (value >= 1 && value <= 15) {
                return value;
            }
        } catch (NumberFormatException e) {
            throw new InvalidInput("INVALID_REQUEST", "limit", "INVALID_TYPE");
        }
        throw new InvalidInput("INVALID_REQUEST", "limit", "OUT_OF_RANGE");
    }

    private static double coordinate(String input, String field, int max) {
        if (input == null) {
            throw new InvalidInput("INVALID_COORDINATES", field, "REQUIRED");
        }
        if (input.length() > 40) {
            throw new InvalidInput("INVALID_COORDINATES", field, "INVALID_FORMAT");
        }
        try {
            BigDecimal value = new BigDecimal(input);
            if (value.abs().compareTo(BigDecimal.valueOf(max)) <= 0) {
                return value.doubleValue();
            }
        } catch (NumberFormatException e) {
            throw new InvalidInput("INVALID_COORDINATES", field, "INVALID_TYPE");
        }
        throw new InvalidInput("INVALID_COORDINATES", field, "OUT_OF_RANGE");
    }

    @ExceptionHandler(InvalidInput.class)
    ResponseEntity<Envelope> invalid(InvalidInput error) {
        return ResponseEntity.badRequest().body(new Envelope(
                new ErrorBody(error.code, "요청값을 확인해 주세요.", List.of(error.detail)), UUID.randomUUID(), Instant.now()));
    }

    @ExceptionHandler(KakaoLocalClient.Failure.class)
    ResponseEntity<Envelope> unavailable(KakaoLocalClient.Failure failure) {
        String code = failure.status == HttpStatus.GATEWAY_TIMEOUT ? "UPSTREAM_TIMEOUT" : "UPSTREAM_UNAVAILABLE";
        return ResponseEntity.status(failure.status).body(new Envelope(
                new ErrorBody(code, "위치 정보를 조회할 수 없습니다. 잠시 후 다시 시도해 주세요.", List.of()),
                UUID.randomUUID(), Instant.now()));
    }

    record SearchResult(String query, List<KakaoLocalClient.Place> items) {}
    record ReverseResult(double latitude, double longitude, String roadAddress, String jibunAddress, String displayName) {}
    record Envelope(ErrorBody error, UUID requestId, Instant timestamp) {}
    record ErrorBody(String code, String message, List<Detail> details) {}
    record Detail(String field, String reason) {}

    static final class InvalidInput extends RuntimeException {
        private final String code;
        private final Detail detail;

        InvalidInput(String code, String field, String reason) {
            this.code = code;
            this.detail = new Detail(field, reason);
        }
    }
}
