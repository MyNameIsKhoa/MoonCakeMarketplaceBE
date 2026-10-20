package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Field null = giữ nguyên. Không cho đổi productId (không reparent). status: DRAFT | ACTIVE | INACTIVE. */
public record UpdateVariantRequest(
        @Size(min = 1, max = 100) String sku,
        @Size(min = 1, max = 255) String variantName,
        @Size(max = 500) String imageUrl,
        @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal basePrice,
        @Min(0) Integer stockQuantity,
        String status) { }
