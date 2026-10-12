package mss.mooncake.catalogstore.client.dto;

/**
 * Mirror DTO của SupplierSummaryResponse trong IdentityService.
 * Chỉ dùng để deserialize response Feign — không import entity/repo của Identity.
 */
public record SupplierSummaryDto(
    Long supplierId,
    Long userId,
    String companyName,
    String taxCode,
    String verificationStatus
) { }
