package mss.mooncake.identity.dto.response;

public record SupplierSummaryResponse(
    Long supplierId,
    Long userId,
    String companyName,
    String taxCode,
    String verificationStatus
) { }
