package mss.mooncake.commerce.exception;

import java.util.Map;
import mss.mooncake.commerce.controller.FinanceController;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice(assignableTypes = FinanceController.class)
public class FinanceExceptionHandler {
    @ExceptionHandler(FinanceException.class)
    public ResponseEntity<Map<String, String>> finance(FinanceException error) {
        return ResponseEntity.status(error.getStatus()).body(Map.of("code", error.getCode(), "message", error.getCode()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_FINANCE_REQUEST", "message", "Invalid request fields"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException error) {
        return ResponseEntity.status(409).body(Map.of("code", "FINANCE_CONSTRAINT_CONFLICT", "message", "Refresh and reconcile before retrying"));
    }
}
