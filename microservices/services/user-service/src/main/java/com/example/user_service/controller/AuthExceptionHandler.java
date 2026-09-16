package com.example.user_service.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

@Slf4j
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(
            Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Invalid authentication request", request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleDenial(
            ResponseStatusException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN
                || status == HttpStatus.CONFLICT) {
            return error(status, exception.getReason(), request);
        }
        return handleFailure(exception, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleFailure(
            Exception exception, HttpServletRequest request) {
        log.error("Authentication request failed", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unable to process authentication request", request);
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? status.getReasonPhrase() : message,
                "path", request.getRequestURI()));
    }
}
