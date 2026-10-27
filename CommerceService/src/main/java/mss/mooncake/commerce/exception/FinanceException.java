package mss.mooncake.commerce.exception;

import org.springframework.http.HttpStatus;

public class FinanceException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public FinanceException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
}
