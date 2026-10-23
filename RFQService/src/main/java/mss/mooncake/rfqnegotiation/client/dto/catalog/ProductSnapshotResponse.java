package mss.mooncake.rfqnegotiation.client.dto.catalog;
import java.math.BigDecimal;
public record ProductSnapshotResponse(Long productId, Long variantId, String productName,
                                      String variantName, String sku, BigDecimal agreedUnitPrice,
                                      Long storeId, Long supplierId, String productCode, String imageUrl, String status) { }
