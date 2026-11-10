package mss.mooncake.commerce.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CartSelectionRequest(@NotNull @Size(max = 1000) List<@NotNull @Positive Long> cartItemIds) { }
