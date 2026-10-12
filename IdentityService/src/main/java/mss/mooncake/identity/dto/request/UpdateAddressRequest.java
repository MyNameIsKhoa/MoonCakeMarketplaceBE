package mss.mooncake.identity.dto.request;

import jakarta.validation.constraints.Size;

public record UpdateAddressRequest(
    @Size(max = 255, message = "Recipient name cannot exceed 255 characters")
    String recipientName,

    @Size(max = 30, message = "Recipient phone cannot exceed 30 characters")
    String recipientPhone,

    @Size(max = 500, message = "Address line cannot exceed 500 characters")
    String addressLine,

    @Size(max = 100, message = "Ward cannot exceed 100 characters")
    String ward,

    @Size(max = 100, message = "District cannot exceed 100 characters")
    String district,

    @Size(max = 100, message = "City cannot exceed 100 characters")
    String city,

    Boolean isDefault
) { }
