package mss.mooncake.identity.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AddressRequest(@NotBlank String receiverName, @NotBlank String phone,
                             @NotBlank String addressDetail, @NotBlank String province,
                             boolean defaultAddress) { }
