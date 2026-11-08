package mss.mooncake.identity.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Internal request from another service. Exactly one recipient field is set: userId, customerId
 * (customer_profile) or supplierId (supplier_profile); Identity resolves the last two to the user.
 * target is a flat JSON object with a "kind" the FE routes on. dedupKey makes retries create one row per user.
 */
public record CreateNotificationRequest(
        @Positive Long userId,
        @Positive Long customerId,
        @Positive Long supplierId,
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,49}", message = "type phải viết hoa, chỉ gồm chữ, số và dấu gạch dưới (tối đa 50 ký tự)") String type,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 1000) String body,
        Map<String, Object> target,
        @Size(max = 150) @Pattern(regexp = "[!-~]+", message = "dedupKey chỉ gồm ký tự ASCII in được, không có khoảng trắng") String dedupKey) {
}
