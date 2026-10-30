package mss.mooncake.identity.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailAddressRequest(
        @Email @NotBlank @Size(max = 255) String email) { }
