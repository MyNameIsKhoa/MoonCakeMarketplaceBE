package mss.mooncake.rfqnegotiation.dto.request;
import jakarta.validation.constraints.NotNull;
public record CreateOrderFromAcceptedQuotationRequest(@NotNull Long quotationId, @NotNull Long customerId,
                                                      @NotNull Long supplierId, @NotNull Long storeId,
                                                      @NotNull Integer quotationVersion) { }
