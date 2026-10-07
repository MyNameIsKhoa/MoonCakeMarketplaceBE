package mss.mooncake.identity.dto.response;

public record UserSummaryResponse(Long userId, Long customerId, String fullName, String email, String phone) { }
