package mss.mooncake.rfqnegotiation.client.dto.identity;
public record UserSummaryResponse(Long userId, Long customerId, String fullName, String email, String phone) { }
