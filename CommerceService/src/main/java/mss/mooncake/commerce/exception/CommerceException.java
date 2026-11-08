package mss.mooncake.commerce.exception;

import org.springframework.http.HttpStatus;

/** Business rule violation with a stable machine {@code code} and a Vietnamese message safe to show users. */
public class CommerceException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public CommerceException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
