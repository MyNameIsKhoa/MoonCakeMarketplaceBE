package mss.mooncake.identity.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SavePayoutAccountRequest(
        @NotBlank @Pattern(regexp = "[0-9]{6}") String bankBin,
        @NotBlank @Pattern(regexp = "[0-9]{6,19}") String accountNumber,
        @NotBlank @Size(max = 100) String accountHolder) { }
