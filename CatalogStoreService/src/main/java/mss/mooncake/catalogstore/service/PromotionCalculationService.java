package mss.mooncake.catalogstore.service;

import mss.mooncake.catalogstore.dto.request.PromotionCalculationRequest;
import mss.mooncake.catalogstore.dto.response.PromotionCalculationResult;

/**
 * Promotion engine cho Commerce (IN08 POST /internal/v1/promotions/calculate).
 * Hỗ trợ PERCENT_DISCOUNT, FIXED_DISCOUNT, BUY_X_GET_Y; trigger và reward có
 * thể là variant khác nhau.
 */
public interface PromotionCalculationService {

    /**
     * Tính giảm giá cho các line của một store tại thời điểm hiện tại (UTC).
     * Giá lấy từ variant.basePrice; chỉ áp dụng promotion ACTIVE có start_at <= now
     * < end_at.
     * Variant không tồn tại hoặc không thuộc store trả 400/404, không âm thầm bỏ
     * qua.
     */
    PromotionCalculationResult calculate(PromotionCalculationRequest request);
}
