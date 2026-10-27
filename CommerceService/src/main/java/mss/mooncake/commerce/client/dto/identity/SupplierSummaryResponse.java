package mss.mooncake.commerce.client.dto.identity;

public record SupplierSummaryResponse(Long supplierId, Long userId, String companyName,
                                      String taxCode, String verificationStatus) { }
