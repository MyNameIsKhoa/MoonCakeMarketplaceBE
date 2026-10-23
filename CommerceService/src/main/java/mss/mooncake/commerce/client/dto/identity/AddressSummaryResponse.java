package mss.mooncake.commerce.client.dto.identity;

public record AddressSummaryResponse(Long addressId, Long customerId, String recipientName,
                                     String recipientPhone, String addressLine, String ward,
                                     String district, String city) { }
