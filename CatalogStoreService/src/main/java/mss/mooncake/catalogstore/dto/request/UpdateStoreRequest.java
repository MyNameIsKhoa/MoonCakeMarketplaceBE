package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

public record UpdateStoreRequest(
    @Size(max = 255, message = "Tên store tối đa 255 ký tự")
    String name,

    @Size(max = 500, message = "URL logo tối đa 500 ký tự")
    String logoUrl,

    @Size(max = 500, message = "URL banner tối đa 500 ký tự")
    String bannerUrl,

    @Size(max = 100, message = "Theme key tối đa 100 ký tự")
    String themeKey,

    String status,

    @Size(max = 2000, message = "Mô tả store tối đa 2000 ký tự")
    String description,
    @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal shippingFee
) {
    public UpdateStoreRequest(String name, String logoUrl, String bannerUrl, String themeKey,
                              String status, String description) {
        this(name, logoUrl, bannerUrl, themeKey, status, description, null);
    }
}
