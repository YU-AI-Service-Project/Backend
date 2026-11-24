package dev.wattup.backend.auth;

import org.springframework.dao.DataAccessException;
import org.springframework.core.MethodParameter;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice(assignableTypes = {AuthController.class, AccountController.class})
class AuthAdvice implements ResponseBodyAdvice<Object> {
    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<AuthFailure.Envelope> handle(AuthFailure failure) {
        return ResponseEntity.status(failure.status).body(failure.response());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<AuthFailure.Envelope> badBody() {
        return handle(new AuthFailure(400, "INVALID_REQUEST"));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<AuthFailure.Envelope> unavailable() {
        return handle(new AuthFailure(503, "AUTH_UNAVAILABLE"));
    }

    @Override
    public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter method, MediaType mediaType,
                                 Class<? extends HttpMessageConverter<?>> converter,
                                 ServerHttpRequest request, ServerHttpResponse response) {
        response.getHeaders().setCacheControl(CacheControl.noStore());
        return body;
    }
}
