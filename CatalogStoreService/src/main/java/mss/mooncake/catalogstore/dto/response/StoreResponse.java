package mss.mooncake.catalogstore.dto.response;
import java.math.BigDecimal;

public record StoreResponse(
    Long storeId,
    Long supplierIdRef,
    String storeCode,
    String name,
    String slug,
    String logoUrl,
    String bannerUrl,
    String themeKey,
    String status,
    String description,
    BigDecimal shippingFee
) {
    public StoreResponse(Long storeId, Long supplierIdRef, String storeCode, String name, String slug,
                         String logoUrl, String bannerUrl, String themeKey, String status, String description) {
        this(storeId, supplierIdRef, storeCode, name, slug, logoUrl, bannerUrl, themeKey, status, description, null);
    }
}
