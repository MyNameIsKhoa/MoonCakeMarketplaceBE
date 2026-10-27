package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

public record StockReservationRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String reservationKey,
        @NotNull OffsetDateTime expiresAt,
        @NotEmpty @Size(max = 100) List<@Valid @NotNull Line> lines) {
    public record Line(@NotNull @Positive Long variantId, @NotNull @Positive Integer quantity) { }
}
