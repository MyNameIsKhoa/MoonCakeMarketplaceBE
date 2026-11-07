package mss.mooncake.identity.dto.request;

import jakarta.validation.constraints.NotBlank;

public record TokenRequest(@NotBlank String token) { }
