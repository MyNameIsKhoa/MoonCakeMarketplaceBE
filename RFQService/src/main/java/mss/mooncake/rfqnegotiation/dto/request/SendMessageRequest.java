package mss.mooncake.rfqnegotiation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String clientMessageId,
        @NotBlank @Pattern(regexp = "TEXT") String messageType,
        @NotBlank @Size(max = 10000) String content) { }
