package dev.wattup.backend.station;

import dev.wattup.backend.prediction.PredictionController;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = {ChargingStationController.class, PredictionController.class})
public class StationApiAdvice {
    @ExceptionHandler(StationApiError.class)
    ResponseEntity<Envelope> handle(StationApiError error) {
        List<Detail> details = error.field == null ? List.of() : List.of(new Detail(error.field, error.reason));
        return ResponseEntity.status(error.status).body(new Envelope(
                new ErrorBody(error.code, error.getMessage(), details), UUID.randomUUID(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Envelope> invalidType(MethodArgumentTypeMismatchException error) {
        String code = switch (error.getName()) {
            case "latitude", "longitude" -> "INVALID_COORDINATES";
            case "radiusM" -> "INVALID_RADIUS";
            default -> "INVALID_REQUEST";
        };
        return handle(StationApiError.binding(code, error.getName(), "INVALID_TYPE"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Envelope> missingParameter(MissingServletRequestParameterException error) {
        return handle(StationApiError.binding("INVALID_REQUEST", error.getParameterName(), "REQUIRED"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Envelope> invalidBody() {
        return handle(StationApiError.binding("INVALID_REQUEST", "body", "INVALID_JSON"));
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<Envelope> databaseFailure() {
        return handle(StationApiError.unavailable());
    }

    record Envelope(ErrorBody error, UUID requestId, Instant timestamp) {
    }

    record ErrorBody(String code, String message, List<Detail> details) {
    }

    record Detail(String field, String reason) {
    }
}
