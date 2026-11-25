package dev.wattup.backend.operations;

import dev.wattup.backend.collection.CollectorOperationsController;
import dev.wattup.backend.prediction.PredictionHealthController;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {CollectorOperationsController.class, PredictionHealthController.class})
class InternalAdvice {
    @ExceptionHandler(InternalFailure.class)
    ResponseEntity<InternalFailure.Envelope> handle(InternalFailure failure) {
        return ResponseEntity.status(failure.status).body(failure.body());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<InternalFailure.Envelope> malformed() {
        return handle(new InternalFailure(400, "INVALID_REQUEST"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<InternalFailure.Envelope> unavailable() {
        return handle(new InternalFailure(503, "UPSTREAM_UNAVAILABLE"));
    }
}
