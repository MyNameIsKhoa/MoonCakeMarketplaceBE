package mss.mooncake.catalogstore.exception;

public class ConflictException extends RuntimeException {
    /** Mã máy đọc được (vd. STORE_SHIPPING_FEE_REQUIRED) để frontend rẽ nhánh; null với lỗi xung đột chung. */
    private final String code;

    public ConflictException(String message) {
        this(null, message);
    }

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
