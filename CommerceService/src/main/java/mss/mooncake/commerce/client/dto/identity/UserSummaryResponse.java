package mss.mooncake.commerce.client.dto.identity;
public record UserSummaryResponse(Long userId, Long customerId, String fullName, String email, String phone) { }
