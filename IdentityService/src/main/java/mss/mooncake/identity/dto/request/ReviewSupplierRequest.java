package mss.mooncake.identity.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import mss.mooncake.identity.enums.SupplierVerificationStatus;

public record ReviewSupplierRequest(
        @NotNull SupplierVerificationStatus verificationStatus,
        @NotBlank @Size(max = 1000) String reason
) { }
