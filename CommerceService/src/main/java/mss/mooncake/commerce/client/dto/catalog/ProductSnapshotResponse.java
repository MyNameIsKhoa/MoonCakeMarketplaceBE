package mss.mooncake.commerce.client.dto.catalog;
import java.math.BigDecimal;
public record ProductSnapshotResponse(Long productId, Long variantId, String productName,
                                      String variantName, String sku, BigDecimal agreedUnitPrice) { }
