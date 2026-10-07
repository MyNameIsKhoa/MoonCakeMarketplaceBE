package mss.mooncake.commerce.dto.request;
import jakarta.validation.constraints.NotNull;
public record CheckoutRequest(@NotNull Long cartId, @NotNull Long addressId) { }
