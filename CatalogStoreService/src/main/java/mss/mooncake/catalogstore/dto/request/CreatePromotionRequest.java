package mss.mooncake.catalogstore.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * startAt/endAt theo UTC; service kiểm tra startAt < endAt. Promotion mới ở
 * trạng thái DRAFT.
 */
public record CreatePromotionRequest(
                @NotNull Long storeId,
                @NotBlank @Size(max = 255) String name,
                @NotNull LocalDateTime startAt,
                @NotNull LocalDateTime endAt,
                @NotEmpty @Size(max = 20) List<@Valid @NotNull PromotionRuleRequest> rules) {
}
