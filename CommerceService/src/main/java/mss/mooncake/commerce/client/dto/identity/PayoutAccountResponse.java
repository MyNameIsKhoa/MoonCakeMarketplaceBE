package mss.mooncake.commerce.client.dto.identity;

public record PayoutAccountResponse(String ownerKind, String bankBin, String bankName,
        String accountNumber, String accountHolder) { }
