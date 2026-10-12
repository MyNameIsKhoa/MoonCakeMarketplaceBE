package mss.mooncake.identity.dto.response;

public record AuthResponse(
    String accessToken,
    String tokenType,
    long expiresIn,
    UserSummaryResponse user
) { }
