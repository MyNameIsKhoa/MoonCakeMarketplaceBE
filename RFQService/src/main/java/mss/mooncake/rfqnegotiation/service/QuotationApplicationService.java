package mss.mooncake.rfqnegotiation.service;
import mss.mooncake.rfqnegotiation.dto.request.AcceptQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.QuotationAcceptedResponse;
public interface QuotationApplicationService {
    QuotationAcceptedResponse acceptQuotation(Long quotationId, Long customerId, AcceptQuotationRequest request);
    // TODO: Enforce versioning, expiry, immutable accepted version, and accept idempotency.
}
