package mss.mooncake.commerce.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AddCartItemRequest(@NotNull @Min(1) Long variantId,
                                 @NotNull @Min(1) Integer quantity) { }
