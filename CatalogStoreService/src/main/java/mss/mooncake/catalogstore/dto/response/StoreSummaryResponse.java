package mss.mooncake.catalogstore.dto.response;
import java.math.BigDecimal;

/**
 * Contract nội bộ: TV3/TV4 dùng để snapshot Store trong order/quotation.
 * Field-level phải ổn định — không đổi tên field khi chưa thống nhất với consumer.
 */
public record StoreSummaryResponse(
    Long storeId,
    Long supplierIdRef,
    String storeCode,
    String name,
    String slug,
    String logoUrl,
    String status,
    BigDecimal shippingFee
) {
    public StoreSummaryResponse(Long storeId, Long supplierIdRef, String storeCode, String name,
                                String slug, String logoUrl, String status) {
        this(storeId, supplierIdRef, storeCode, name, slug, logoUrl, status, null);
    }
}
