package mss.mooncake.catalogstore.dto.response;

import java.math.BigDecimal;
import io.swagger.v3.oas.annotations.media.Schema;

public record ProductSnapshotResponse(Long productId, Long variantId, String productName,
                                      String variantName, String sku,
                                      @Schema(description = "Legacy field containing the Catalog base price; negotiated quotation prices remain in RFQ")
                                      BigDecimal agreedUnitPrice, Long storeId, Long supplierId,
                                      String productCode, String imageUrl, String status) {
    public ProductSnapshotResponse(Long productId, Long variantId, String productName,
                                   String variantName, String sku, BigDecimal agreedUnitPrice) {
        this(productId, variantId, productName, variantName, sku, agreedUnitPrice, null, null, null, null, null);
    }
}
