package mss.mooncake.identity.dto.response;

import java.time.LocalDateTime;

public record PayoutAccountResponse(String ownerKind, String bankBin, String bankName,
        String accountNumber, String accountHolder, LocalDateTime updatedAt) { }
