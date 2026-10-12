package mss.mooncake.identity.dto.response;

import java.time.LocalDateTime;

public record UserProfileResponse(
    Long userId,
    Long customerId,
    String email,
    String fullName,
    String phone,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) { }
