package com.youtube.analytics.exception;

import com.youtube.analytics.model.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ApiResponse<Void>> handleWebClientError(
            WebClientResponseException ex) {

        log.warn("YouTube API call failed | status={}", ex.getStatusCode());

        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error("YouTube API request failed"));
    }

    @ExceptionHandler(YouTubeAnalyticsApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleYouTubeAnalyticsError(YouTubeAnalyticsApiException ex) {
        int upstreamStatus = ex.getStatusCode().value();
        HttpStatus responseStatus = switch (upstreamStatus) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_GATEWAY;
        };
        String message = switch (upstreamStatus) {
            case 400 -> "YouTube Analytics rejected the report request";
            case 401 -> "YouTube authorization is missing or expired. Please sign in again.";
            case 403 -> "You are not allowed to access analytics for this video";
            case 404 -> "The requested YouTube analytics resource was not found";
            case 429 -> "YouTube Analytics rate limit exceeded. Please try again later.";
            default -> "YouTube Analytics is temporarily unavailable";
        };
        log.warn("YouTube Analytics API failed | upstreamStatus={}", upstreamStatus);
        return ResponseEntity.status(responseStatus).body(ApiResponse.error(message));
    }

    @ExceptionHandler(InvalidAnalyticsRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidAnalyticsRequest(InvalidAnalyticsRequestException ex) {
        log.warn("Invalid analytics request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.error("Configuration or state error: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", errors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Validation error: " + errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String errors = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining(", "));
        log.warn("Constraint violation: {}", errors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Validation error: " + errors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Unexpected error: " + ex.getMessage()));
    }
}
