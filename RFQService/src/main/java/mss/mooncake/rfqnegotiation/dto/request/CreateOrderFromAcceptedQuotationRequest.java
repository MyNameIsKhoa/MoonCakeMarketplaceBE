package mss.mooncake.rfqnegotiation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Immutable, authenticated RFQ snapshot. Public callers never supply this DTO. */
public record CreateOrderFromAcceptedQuotationRequest(
        @NotNull @Positive Long rfqId, @NotNull @Positive Long quotationId,
        @NotNull @Positive Integer quotationVersion, @NotNull LocalDateTime acceptedAt,
        @NotNull @Positive Long customerId, @NotNull @Positive Long supplierId, @NotNull @Positive Long storeId,
        @NotBlank @Size(max = 100) String idempotencyKey,
        @NotNull @Valid StoreSnapshot storeSnapshot,
        @NotNull @Valid AddressSnapshot deliveryAddressSnapshot,
        @NotNull @Valid Terms terms,
        @NotEmpty @Size(max = 100) List<@Valid Item> items,
        @NotNull @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal subtotal,
        @NotNull @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal totalAmount) {
    public record StoreSnapshot(@NotBlank @Size(max=255) String storeName, @NotBlank @Size(max=255) String supplierName) { }
    public record AddressSnapshot(@NotNull @Positive Long sourceAddressId,
            @NotBlank @Size(max=255) String recipientName, @NotBlank @Size(max=30) String recipientPhone,
            @NotBlank @Size(max=500) String addressLine, @Size(max=100) String ward, @Size(max=100) String district,
            @NotBlank @Size(max=100) String city) { }
    public record Terms(@NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer=3,fraction=2) BigDecimal depositPercent,
            @NotNull LocalDate deliveryDate, @Size(max=100) String shippingMethod,
            @NotNull @DecimalMin("0.00") @Digits(integer=16,fraction=2) BigDecimal shippingFee,
            @Size(max=10000) String paymentTerms) { }
    public record Item(@Positive Long variantId, @Size(max=100) String productCodeSnapshot,
            @NotBlank @Size(max=255) String productNameSnapshot, @Size(max=255) String variantNameSnapshot,
            @Size(max=100) String skuSnapshot, @Size(max=500) String imageUrlSnapshot,
            @NotNull @Positive Integer quantity,
            @NotNull @DecimalMin("0.00") @Digits(integer=16,fraction=2) BigDecimal unitPrice,
            @NotNull @DecimalMin("0.00") @Digits(integer=16,fraction=2) BigDecimal discountAmount,
            @NotNull @DecimalMin("0.00") @Digits(integer=16,fraction=2) BigDecimal subtotal) { }
}
