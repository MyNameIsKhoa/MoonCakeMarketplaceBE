package mss.mooncake.identity.dto.response;

import java.time.LocalDateTime;

public record SupplierVerificationAuditResponse(Long auditId, Long supplierId, Long actorUserId,
        String previousStatus, String newStatus, String reason, LocalDateTime actedAt) { }
