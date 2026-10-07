package mss.mooncake.commerce.dto.request;
import jakarta.validation.constraints.NotNull;
public record CreateOrderFromQuotationRequest(@NotNull Long quotationId, @NotNull Long customerId,
                                              @NotNull Long supplierId, @NotNull Long storeId,
                                              @NotNull Integer quotationVersion) { }
