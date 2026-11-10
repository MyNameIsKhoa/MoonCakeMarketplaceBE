package mss.mooncake.identity.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
        @JsonAlias("token") @NotBlank String code,
        @NotBlank @Size(min = 6, max = 100, message = "Password must be between 6 and 100 characters") String newPassword) { }
