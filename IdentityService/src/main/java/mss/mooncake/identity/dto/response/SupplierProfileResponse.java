package mss.mooncake.identity.dto.response;

import java.time.LocalDateTime;

public record SupplierProfileResponse(
    Long supplierId,
    Long userId,
    String companyName,
    String taxCode,
    String verificationStatus,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) { }
