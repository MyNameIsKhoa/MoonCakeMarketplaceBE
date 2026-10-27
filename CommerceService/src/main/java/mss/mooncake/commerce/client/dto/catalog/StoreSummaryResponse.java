package mss.mooncake.commerce.client.dto.catalog;
import java.math.BigDecimal;

public record StoreSummaryResponse(Long storeId, Long supplierIdRef, String storeCode,
                                   String name, String slug, String logoUrl, String status,
                                   BigDecimal shippingFee) {
    public StoreSummaryResponse(Long storeId, Long supplierIdRef, String storeCode, String name,
                                String slug, String logoUrl, String status) {
        this(storeId, supplierIdRef, storeCode, name, slug, logoUrl, status, null);
    }
}
