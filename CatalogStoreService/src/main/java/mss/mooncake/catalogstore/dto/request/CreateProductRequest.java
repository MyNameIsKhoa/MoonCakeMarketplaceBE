package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** productCode do server sinh; giá nằm ở variant, không ở product. */
public record CreateProductRequest(
        @NotNull Long storeId,
        @NotNull Long categoryId,
        @NotBlank @Size(max = 255) String name,
        String description) {
}
