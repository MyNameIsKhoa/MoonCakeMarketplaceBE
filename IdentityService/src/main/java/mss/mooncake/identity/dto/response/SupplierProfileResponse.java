package mss.mooncake.identity.dto.response;

import java.time.LocalDateTime;

/**
 * lastDecision*: quyết định gần nhất của admin (VERIFIED / REJECTED / SUSPENDED) kèm lý do, để supplier biết vì sao
 * hồ sơ bị từ chối hoặc tạm khóa. null khi hồ sơ chưa từng được admin xét.
 */
public record SupplierProfileResponse(
    Long supplierId,
    Long userId,
    String companyName,
    String taxCode,
    String verificationStatus,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String lastDecisionStatus,
    String lastDecisionReason,
    LocalDateTime lastDecisionAt
) {
    public SupplierProfileResponse(Long supplierId, Long userId, String companyName, String taxCode,
                                   String verificationStatus, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(supplierId, userId, companyName, taxCode, verificationStatus, createdAt, updatedAt, null, null, null);
    }
}
