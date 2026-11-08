package mss.mooncake.identity.dto.request;

/** Regex dùng chung cho Bean Validation của các request DTO. */
public final class ValidationPatterns {

    /**
     * Rỗng (không nhập / giữ nguyên) hoặc số điện thoại: chữ số, khoảng trắng, '.', '-', có thể bắt đầu bằng '+',
     * bắt đầu và kết thúc bằng chữ số (vd. "0901 234 567", "+84 901-234-567").
     */
    public static final String PHONE_OR_EMPTY = "^$|^\\+?[0-9][0-9 .-]{6,28}[0-9]$";

    public static final String PHONE_MESSAGE =
            "Số điện thoại không hợp lệ: chỉ gồm chữ số, khoảng trắng, '.', '-' (có thể bắt đầu bằng '+'), tối thiểu 8 chữ số/ký tự.";

    private ValidationPatterns() {
    }
}
