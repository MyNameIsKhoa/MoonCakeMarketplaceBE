package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * IN08: caller chỉ gửi variant + số lượng. Giá, rule và thời điểm tính do
 * Catalog quyết định;
 * không nhận giá từ caller.
 */
public record PromotionCalculationRequest(
        @NotNull @Positive Long storeId,
        @NotEmpty @Size(max = 100) List<@Valid @NotNull Line> lines) {

    public record Line(@NotNull @Positive Long variantId, @NotNull @Positive Integer quantity) {
    }
}
