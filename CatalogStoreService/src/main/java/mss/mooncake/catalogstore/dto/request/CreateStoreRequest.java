package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateStoreRequest(
        @NotBlank(message = "Tên store không được để trống") @Size(max = 255, message = "Tên store tối đa 255 ký tự") String name,

        @Size(max = 500, message = "URL logo tối đa 500 ký tự")
        @Pattern(regexp = HTTP_URL_OR_EMPTY, message = "URL logo phải bắt đầu bằng http:// hoặc https://") String logoUrl,

        @Size(max = 500, message = "URL banner tối đa 500 ký tự")
        @Pattern(regexp = HTTP_URL_OR_EMPTY, message = "URL banner phải bắt đầu bằng http:// hoặc https://") String bannerUrl,

        @Size(max = 100, message = "Theme key tối đa 100 ký tự") String themeKey,

        @Size(max = 2000, message = "Mô tả store tối đa 2000 ký tự") String description,

        /** Không bắt buộc khi tạo; store chỉ được chuyển sang ACTIVE khi đã có phí vận chuyển. */
        @DecimalMin(value = "0.00", message = "Phí vận chuyển không được âm")
        @Digits(integer = 16, fraction = 2, message = "Phí vận chuyển tối đa 16 chữ số phần nguyên và 2 chữ số thập phân")
        BigDecimal shippingFee) {

    /** Rỗng hoặc URL http(s) không chứa khoảng trắng; chặn javascript:, data:, ftp:... dùng làm ảnh storefront. */
    public static final String HTTP_URL_OR_EMPTY = "^$|^(?i)https?://\\S+$";

    public CreateStoreRequest(String name, String logoUrl, String bannerUrl, String themeKey, String description) {
        this(name, logoUrl, bannerUrl, themeKey, description, null);
    }
}
