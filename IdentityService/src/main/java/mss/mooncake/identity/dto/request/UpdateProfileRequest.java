package mss.mooncake.identity.dto.request;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
    @Size(max = 255, message = "Full name cannot exceed 255 characters")
    String fullName,

    @Size(max = 30, message = "Phone cannot exceed 30 characters")
    String phone
) { }
