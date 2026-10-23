package mss.mooncake.rfqnegotiation.service;
import mss.mooncake.rfqnegotiation.dto.request.AcceptQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.QuotationAcceptedResponse;
public interface QuotationApplicationService {
    QuotationAcceptedResponse acceptQuotation(Long quotationId, AcceptQuotationRequest request, String idempotencyKey);
    QuotationAcceptedResponse getOrderLink(Long quotationId);
    void recoverPendingConversions();
}
