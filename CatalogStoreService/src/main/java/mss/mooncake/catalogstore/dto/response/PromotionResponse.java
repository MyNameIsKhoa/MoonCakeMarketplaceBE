package mss.mooncake.catalogstore.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record PromotionResponse(Long promotionId, Long storeId, String name, LocalDateTime startAt,
                                LocalDateTime endAt, String status, List<PromotionRuleResponse> rules) { }
