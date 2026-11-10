package mss.mooncake.catalogstore.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.core.PropertyReferenceException;
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
import tools.jackson.core.JacksonException;

/**
 * Mọi lỗi trả body {@link ErrorResponse} ({@code status, message, timestamp}, thêm {@code code} khi có mã máy đọc)
 * với message tiếng Việt; không trả nội dung exception Java/SQL/Jackson ra client (lỗi bất ngờ chỉ ghi log).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(), message, LocalDateTime.now()));
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
                .body(new ErrorResponse(HttpStatus.CONFLICT.value(), ex.getMessage(), LocalDateTime.now(), ex.getCode()));
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
            // Lỗi chuyển kiểu (vd. ?page=abc) mang message kỹ thuật của Spring; thay bằng câu tiếng Việt.
            errors.put(fe.getField(), fe.isBindingFailure() ? "Giá trị không đúng định dạng." : fe.getDefaultMessage());
        }
        Map<String, Object> body = new HashMap<>();
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("errors", errors);
        body.put("timestamp", LocalDateTime.now());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        String field = jsonFieldPath(ex);
        return error(HttpStatus.BAD_REQUEST, field == null
                ? "Dữ liệu gửi lên không đúng định dạng JSON hoặc sai kiểu dữ liệu."
                : "Giá trị của trường '" + field
                        + "' không hợp lệ (sai kiểu, sai định dạng hoặc không thuộc danh sách cho phép).");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "Giá trị của tham số '" + ex.getName() + "' không hợp lệ.";
        Class<?> type = ex.getRequiredType();
        if (type != null && type.isEnum()) {
            message += " Giá trị cho phép: " + Arrays.stream(type.getEnumConstants())
                    .map(Object::toString).collect(Collectors.joining(", ")) + ".";
        }
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, "Thiếu tham số bắt buộc: " + ex.getParameterName() + ".");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return error(HttpStatus.BAD_REQUEST, "Thiếu header bắt buộc: " + ex.getHeaderName() + ".");
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handlePropertyReference(PropertyReferenceException ex) {
        return error(HttpStatus.BAD_REQUEST, invalidSortMessage(ex));
    }

    /** Spring Data bọc PropertyReferenceException (sort theo trường không tồn tại) trong exception này. */
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDataAccessApiUsage(InvalidDataAccessApiUsageException ex) {
        PropertyReferenceException propertyError = findCause(ex, PropertyReferenceException.class);
        if (propertyError != null) {
            return error(HttpStatus.BAD_REQUEST, invalidSortMessage(propertyError));
        }
        return handleGeneric(ex);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        if (isUniqueViolation(ex)) {
            return error(HttpStatus.CONFLICT, "Dữ liệu bị trùng với bản ghi đã tồn tại. Vui lòng kiểm tra lại.");
        }
        return error(HttpStatus.BAD_REQUEST,
                "Dữ liệu vi phạm ràng buộc của hệ thống. Vui lòng kiểm tra lại các giá trị đã nhập.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        // Lỗi chuẩn của Spring MVC (404 không có route, 405, 415 sai Content-Type...) giữ đúng status của nó.
        if (ex instanceof org.springframework.web.ErrorResponse springError
                && springError.getStatusCode().value() < 500) {
            HttpStatusCode code = springError.getStatusCode();
            return ResponseEntity.status(code)
                    .body(new ErrorResponse(code.value(), springErrorMessage(code.value()), LocalDateTime.now()));
        }
        log.error("Unexpected error", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống. Vui lòng thử lại sau.");
    }

    private static String springErrorMessage(int status) {
        return switch (status) {
            case 413 -> "Ảnh tải lên vượt quá giới hạn 5 MiB.";
            case 404 -> "Không tìm thấy đường dẫn yêu cầu.";
            case 405 -> "Phương thức HTTP không được hỗ trợ cho đường dẫn này.";
            case 406 -> "Định dạng phản hồi yêu cầu không được hỗ trợ.";
            case 415 -> "Content-Type không được hỗ trợ. Vui lòng gửi application/json hoặc multipart/form-data theo đường dẫn yêu cầu.";
            default -> "Yêu cầu không hợp lệ.";
        };
    }

    private static String invalidSortMessage(PropertyReferenceException ex) {
        return "Trường sắp xếp hoặc lọc không hợp lệ: " + ex.getPropertyName() + ".";
    }

    /** Đường dẫn trường JSON gây lỗi (vd. "rules[0].ruleType"), hoặc null nếu là lỗi cú pháp JSON. */
    private static String jsonFieldPath(Throwable ex) {
        JacksonException jackson = findCause(ex, JacksonException.class);
        if (jackson == null || jackson.getPath().isEmpty()) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : jackson.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!path.isEmpty()) path.append('.');
                path.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? null : path.toString();
    }

    /** SQL Server 2627/2601, SQLState 23505 (H2/PostgreSQL) hoặc DuplicateKeyException của Spring. */
    private static boolean isUniqueViolation(Throwable ex) {
        if (ex instanceof DuplicateKeyException) return true;
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql
                    && (sql.getErrorCode() == 2627 || sql.getErrorCode() == 2601 || "23505".equals(sql.getSQLState()))) {
                return true;
            }
            String message = t.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("unique") || lower.contains("duplicate key")) return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) return type.cast(t);
            if (t.getCause() == t) break;
        }
        return null;
    }

    /** {@code code} chỉ xuất hiện khi lỗi có mã máy đọc (vd. STORE_SHIPPING_FEE_REQUIRED). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(int status, String message, LocalDateTime timestamp, String code) {
        public ErrorResponse(int status, String message, LocalDateTime timestamp) {
            this(status, message, timestamp, null);
        }
    }
}
