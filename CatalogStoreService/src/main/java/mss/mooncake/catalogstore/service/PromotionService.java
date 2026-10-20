package mss.mooncake.catalogstore.service;

import java.util.List;
import mss.mooncake.catalogstore.dto.request.CreatePromotionRequest;
import mss.mooncake.catalogstore.dto.request.PromotionRuleRequest;
import mss.mooncake.catalogstore.dto.request.UpdatePromotionRequest;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.dto.response.PromotionResponse;
import org.springframework.data.domain.Pageable;

/**
 * Quản lý promotion của store (CA16–CA20). Việc tính giảm giá nằm ở
 * {@link PromotionCalculationService}.
 */
public interface PromotionService {

    /**
     * CA16 — mine=false: promotion ACTIVE của store ACTIVE (lọc theo storeId nếu
     * có);
     * mine=true: mọi promotion trong các store của supplier đang login.
     */
    PageResponse<PromotionResponse> getPromotions(Long storeId, boolean mine, Long userId, Pageable pageable);

    /**
     * CA17 — chi tiết kèm rules. Promotion không ACTIVE chỉ owner xem được.
     */
    PromotionResponse getPromotion(Long promotionId, Long userId);

    /**
     * CA18 — tạo promotion DRAFT kèm rules. Owner store; startAt < endAt;
     * field rule đúng theo ruleType; target/reward variant thuộc cùng store.
     */
    PromotionResponse createPromotion(CreatePromotionRequest request, Long userId);

    /**
     * CA19 — cập nhật name/startAt/endAt/status. Không đổi storeId.
     */
    PromotionResponse updatePromotion(Long promotionId, UpdatePromotionRequest request, Long userId);

    /**
     * CA20 — thay toàn bộ rules trong một transaction (xóa cũ, ghi mới), cùng các
     * kiểm tra như CA18.
     */
    PromotionResponse replaceRules(Long promotionId, List<PromotionRuleRequest> rules, Long userId);
}
