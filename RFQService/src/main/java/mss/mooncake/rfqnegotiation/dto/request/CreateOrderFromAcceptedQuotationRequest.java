package mss.mooncake.rfqnegotiation.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// Built by RFQ after verifying the accepted quotation and buyer; never copied from the public accept body.
public record CreateOrderFromAcceptedQuotationRequest(
        Long rfqId, Long quotationId, Integer quotationVersion, OffsetDateTime acceptedAt,
        Long customerId, Long supplierId, Long storeId, StoreSnapshot storeSnapshot,
        DeliveryAddressSnapshot deliveryAddressSnapshot, Terms terms, List<Item> items,
        BigDecimal subtotal, BigDecimal totalAmount) {

    public record StoreSnapshot(String storeName, String supplierName) { }
    public record DeliveryAddressSnapshot(Long sourceAddressId, String recipientName,
                                          String recipientPhone, String addressLine, String ward,
                                          String district, String city) { }
    public record Terms(BigDecimal depositPercent, LocalDate deliveryDate, String shippingMethod,
                        BigDecimal shippingFee, String paymentTerms) { }
    public record Item(Long variantId, String productCodeSnapshot, String productNameSnapshot,
                       String variantNameSnapshot, String skuSnapshot, String imageUrlSnapshot,
                       Integer quantity, BigDecimal unitPrice, BigDecimal discountAmount,
                       BigDecimal subtotal) { }
}
