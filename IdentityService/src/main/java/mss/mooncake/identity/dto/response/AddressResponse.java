package mss.mooncake.identity.dto.response;

public record AddressResponse(
    Long addressId,
    Long customerId,
    String recipientName,
    String recipientPhone,
    String addressLine,
    String ward,
    String district,
    String city,
    boolean isDefault
) { }
