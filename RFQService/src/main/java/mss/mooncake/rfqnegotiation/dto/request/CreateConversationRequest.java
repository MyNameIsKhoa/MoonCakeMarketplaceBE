package mss.mooncake.rfqnegotiation.dto.request;

import jakarta.validation.constraints.Positive;

/** Buyers choose a supplier; supplier callers omit this field and use their own profile. */
public record CreateConversationRequest(@Positive Long supplierId) { }
