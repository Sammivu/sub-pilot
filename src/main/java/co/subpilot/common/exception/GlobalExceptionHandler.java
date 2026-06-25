package co.subpilot.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Centralised exception handler.
 *
 * Every error response follows the same shape:
 * {
 *   "error": {
 *     "code": "plan_not_found",
 *     "message": "Human-readable message",
 *     "request_id": "req_...",
 *     "timestamp": "2025-07-01T10:00:00Z"
 *   }
 * }
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── Domain exceptions ────────────────────────────────────────────────────

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, WebRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRule(BusinessRuleException ex, WebRequest request) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransition(InvalidStateTransitionException ex) {
        return buildResponse(HttpStatus.CONFLICT, "invalid_state_transition", ex.getMessage());
    }

    // ── Validation ───────────────────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        var body = ErrorResponse.of("validation_error", "Request validation failed");
        body.error().put("fields", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // ── Security ─────────────────────────────────────────────────────────────

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid email or password.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, "access_denied", "You do not have permission to perform this action.");
    }

    // ── Fallback ─────────────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unexpected error on request {}: {}", request.getDescription(false), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred. Please try again.");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }

    // ── Error response record ─────────────────────────────────────────────────

    public record ErrorResponse(Map<String, Object> error) {
        static ErrorResponse of(String code, String message) {
            Map<String, Object> body = new HashMap<>();
            body.put("code", code);
            body.put("message", message);
            body.put("request_id", "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            body.put("timestamp", Instant.now().toString());
            return new ErrorResponse(body);
        }
    }
}
