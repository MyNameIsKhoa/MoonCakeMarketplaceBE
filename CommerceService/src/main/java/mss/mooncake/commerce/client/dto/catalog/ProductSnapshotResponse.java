package mss.mooncake.commerce.client.dto.catalog;
import java.math.BigDecimal;
public record ProductSnapshotResponse(Long productId, Long variantId, String productName,
                                      String variantName, String sku, BigDecimal agreedUnitPrice,
                                      Long storeId, Long supplierId, String productCode, String imageUrl, String status) {
    public ProductSnapshotResponse(Long productId, Long variantId, String productName,
                                   String variantName, String sku, BigDecimal agreedUnitPrice) {
        this(productId, variantId, productName, variantName, sku, agreedUnitPrice, null, null, null, null, null);
    }
}
