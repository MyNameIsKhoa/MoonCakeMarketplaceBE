package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ProductRequest(@NotNull Long storeId, @NotBlank String productName,
                             @NotNull BigDecimal basePrice) { }
