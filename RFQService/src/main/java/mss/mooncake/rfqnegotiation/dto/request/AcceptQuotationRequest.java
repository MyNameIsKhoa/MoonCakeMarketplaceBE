package mss.mooncake.rfqnegotiation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AcceptQuotationRequest(@NotNull @Positive Integer quotationVersion,
                                     @NotNull @Positive Long addressId) { }
