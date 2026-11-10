package mss.mooncake.commerce.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

public record CheckoutRequest(@NotNull @Positive Long addressId,
                              @NotNull @Valid CheckoutSelectionRequest selection,
                              @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String quote) { }
