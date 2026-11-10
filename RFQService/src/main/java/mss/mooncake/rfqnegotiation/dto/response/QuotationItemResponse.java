package mss.mooncake.rfqnegotiation.dto.response;

import java.util.Objects;
import mss.mooncake.rfqnegotiation.entity.QuotationItemEntity;

public record QuotationItemResponse(String quotationItemId, String variantId, String itemNameSnapshot,
                                    String skuSnapshot, Integer quantity, String unitPrice,
                                    String discountAmount, String subtotal, String customizationNote, String imageUrlSnapshot) {
    public static QuotationItemResponse from(QuotationItemEntity item) {
        return new QuotationItemResponse(Objects.toString(item.getId(), null),
                Objects.toString(item.getVariantIdRef(), null), item.getItemNameSnapshot(),
                item.getSkuSnapshot(), item.getQuantity(), item.getUnitPrice().toPlainString(),
                item.getDiscountAmount().toPlainString(), item.getSubtotal().toPlainString(), item.getCustomizationNote(),
                item.orderImageUrl());
    }
}
