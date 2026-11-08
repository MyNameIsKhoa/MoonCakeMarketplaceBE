package mss.mooncake.commerce.exception;

import feign.FeignException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FinanceException.class)
    public ResponseEntity<Map<String, String>> handleFinance(FinanceException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of("code", ex.getCode(), "message", ex.getCode()));
    }

    @ExceptionHandler(CommerceException.class)
    public ResponseEntity<ApiError> handleCommerce(CommerceException ex) {
        return coded(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(new ErrorResponse(ex.getStatusCode().value(), ex.getReason(), LocalDateTime.now()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(HttpStatus.NOT_FOUND.value(), ex.getMessage(), LocalDateTime.now()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(HttpStatus.FORBIDDEN.value(), ex.getMessage(), LocalDateTime.now()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(HttpStatus.CONFLICT.value(), ex.getMessage(), LocalDateTime.now()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage(), LocalDateTime.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        Map<String, Object> body = new HashMap<>();
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("errors", errors);
        body.put("timestamp", LocalDateTime.now());
        return ResponseEntity.badRequest().body(body);
    }

    /** Catalog/Identity errors: stable code and Vietnamese message, never the internal URL or remote body. */
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<ApiError> handleDownstream(FeignException ex) {
        log.warn("Downstream call failed: HTTP {} {}", ex.status(), ex.getMessage());
        String url = ex.request() == null ? "" : ex.request().url();
        String remote = ex.contentUTF8() == null ? "" : ex.contentUTF8().toLowerCase(Locale.ROOT);
        return switch (ex.status()) {
            case 404 -> url.contains("/addresses/")
                    ? coded(HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND", "Không tìm thấy địa chỉ giao hàng")
                    : url.contains("/suppliers/") || url.contains("/users/")
                    ? coded(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Không tìm thấy thông tin tài khoản")
                    : url.contains("/stores/")
                    ? coded(HttpStatus.NOT_FOUND, "STORE_NOT_FOUND", "Không tìm thấy cửa hàng")
                    : coded(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Không tìm thấy sản phẩm");
            case 409 -> remote.contains("stock")
                    ? coded(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "Sản phẩm không đủ tồn kho cho số lượng yêu cầu")
                    : remote.contains("not active")
                    ? coded(HttpStatus.CONFLICT, "PRODUCT_UNAVAILABLE", "Sản phẩm hoặc cửa hàng hiện đã ngừng bán")
                    : coded(HttpStatus.CONFLICT, "DOWNSTREAM_CONFLICT",
                            "Dữ liệu sản phẩm vừa thay đổi, vui lòng tải lại và thử lại");
            case 400, 422 -> coded(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Yêu cầu không hợp lệ");
            // 401/403 mean our service token is misconfigured, 5xx/-1 that the service is down: not the user's fault.
            default -> coded(HttpStatus.SERVICE_UNAVAILABLE, "DOWNSTREAM_UNAVAILABLE",
                    "Dịch vụ liên quan tạm thời không khả dụng, vui lòng thử lại sau");
        };
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex) {
        return "Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())
                ? coded(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Thiếu header Idempotency-Key")
                : coded(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "Thiếu header " + ex.getHeaderName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex) {
        return coded(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", "Thiếu tham số " + ex.getParameterName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return coded(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Nội dung yêu cầu không đúng định dạng JSON");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return coded(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Tham số " + ex.getName() + " không hợp lệ");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex) {
        // Framework errors (unknown route, wrong method...) keep their own 4xx status.
        if (ex instanceof org.springframework.web.ErrorResponse framework
                && framework.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = framework.getStatusCode();
            return ResponseEntity.status(status).body(new ApiError(status.value(), "REQUEST_REJECTED",
                    "Yêu cầu không được hỗ trợ", LocalDateTime.now()));
        }
        log.error("Unhandled error", ex);
        return coded(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Lỗi hệ thống, vui lòng thử lại sau");
    }

    private static ResponseEntity<ApiError> coded(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), code, message, LocalDateTime.now()));
    }

    public record ErrorResponse(int status, String message, LocalDateTime timestamp) { }

    /** Error with a stable machine code; {@code message} is safe to show to users. */
    public record ApiError(int status, String code, String message, LocalDateTime timestamp) { }
}
