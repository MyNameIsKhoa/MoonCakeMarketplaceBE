package mss.mooncake.commerce.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record CreateOrderFromQuotationRequest(
        @NotNull @Positive Long rfqId, @NotNull @Positive Long quotationId,
        @NotNull @Positive Integer quotationVersion, @NotNull OffsetDateTime acceptedAt,
        @NotNull @Positive Long customerId, @NotNull @Positive Long supplierId,
        @NotNull @Positive Long storeId, @NotNull @Valid StoreSnapshot storeSnapshot,
        @NotNull @Valid DeliveryAddressSnapshot deliveryAddressSnapshot,
        @NotNull @Valid Terms terms, @NotEmpty List<@Valid Item> items,
        @NotNull @DecimalMin("0.00") BigDecimal subtotal,
        @NotNull @DecimalMin("0.00") BigDecimal totalAmount) {

    public record StoreSnapshot(@NotBlank String storeName, @NotBlank String supplierName) { }
    public record DeliveryAddressSnapshot(@NotNull @Positive Long sourceAddressId,
                                          @NotBlank String recipientName, @NotBlank String recipientPhone,
                                          @NotBlank String addressLine, String ward, String district,
                                          @NotBlank String city) { }
    public record Terms(@NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal depositPercent,
                        @NotNull LocalDate deliveryDate, String shippingMethod,
                        @NotNull @DecimalMin("0.00") BigDecimal shippingFee,
                        String paymentTerms) { }
    public record Item(Long variantId, String productCodeSnapshot,
                       @NotBlank String productNameSnapshot, String variantNameSnapshot,
                       String skuSnapshot, String imageUrlSnapshot, @NotNull @Positive Integer quantity,
                       @NotNull @DecimalMin("0.00") BigDecimal unitPrice,
                       @NotNull @DecimalMin("0.00") BigDecimal discountAmount,
                       @NotNull @DecimalMin("0.00") BigDecimal subtotal) { }
}
