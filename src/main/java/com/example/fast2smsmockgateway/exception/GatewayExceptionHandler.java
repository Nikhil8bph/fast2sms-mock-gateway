package com.example.fast2smsmockgateway.exception;

import com.example.fast2smsmockgateway.dtos.response.ErrorResponse;
import com.example.fast2smsmockgateway.dtos.response.FieldErrorResponse;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestControllerAdvice
public class GatewayExceptionHandler {
    @ExceptionHandler(GatewayException.class)
    ResponseEntity<?> gatewayError(GatewayException exception, HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (exception.field() != null && Set.of("/dev/whatsapp", "/dev/whatsapp-session",
                "/dev/whatsapp-direct-send", "/dev/rcs").contains(path)) {
            return ResponseEntity.status(exception.status()).body(new FieldErrorResponse(false,
                    List.of(exception.field()), Map.of(exception.field(), List.of(exception.getMessage()))));
        }
        return ResponseEntity.status(exception.status())
                .body(new ErrorResponse(false, exception.code(), exception.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class})
    ResponseEntity<ErrorResponse> invalidRequest() {
        return ResponseEntity.badRequest().body(new ErrorResponse(false, 400, "Invalid request parameters or body"));
    }

    @ExceptionHandler({ConcurrencyFailureException.class, DataIntegrityViolationException.class})
    ResponseEntity<ErrorResponse> updateConflict() {
        return ResponseEntity.status(409).body(new ErrorResponse(false, 409, "Concurrent update; retry the request"));
    }
}
