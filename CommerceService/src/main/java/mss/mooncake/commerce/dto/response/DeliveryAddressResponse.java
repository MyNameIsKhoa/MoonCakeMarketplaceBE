package mss.mooncake.commerce.dto.response;

public record DeliveryAddressResponse(Long sourceAddressIdRef, String recipientName,
                                      String recipientPhone, String addressLine, String ward,
                                      String district, String city) { }
