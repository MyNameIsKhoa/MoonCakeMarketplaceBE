package mss.mooncake.rfqnegotiation.dto.response;
public record QuotationAcceptedResponse(Long quotationId, Long rfqId, Long customerId,
                                        Long supplierId, Long storeId, int versionNo) { }
