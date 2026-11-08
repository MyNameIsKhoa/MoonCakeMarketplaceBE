package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import mss.mooncake.catalogstore.dto.request.StockReservationRequest.Line;

/**
 * Returns specific confirmed lines of a reservation to stock (a partially cancelled checkout). The caller's
 * {@code restoreKey} makes it idempotent: a retry with the same key and lines restores nothing twice.
 */
public record StockRestoreRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,150}") String restoreKey,
        @NotEmpty @Size(max = 100) List<@Valid @NotNull Line> lines) { }
