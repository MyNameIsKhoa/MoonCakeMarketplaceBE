package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateVariantRequest(
                @NotBlank @Size(max = 100) String sku,
                @NotBlank @Size(max = 255) String variantName,
                @Size(max = 500) String imageUrl,
                @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal basePrice,
                @NotNull @Min(0) Integer stockQuantity) {
}
