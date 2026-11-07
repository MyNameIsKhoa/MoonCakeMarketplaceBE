package mss.mooncake.identity.dto.request;

import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(@NotBlank(message = "Google ID token is required") String idToken) { }
