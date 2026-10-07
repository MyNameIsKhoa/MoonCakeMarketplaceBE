package mss.mooncake.rfqnegotiation.service;
import mss.mooncake.rfqnegotiation.dto.request.CreateOrderFromAcceptedQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.QuotationAcceptedResponse;
public interface QuotationApplicationService {
    QuotationAcceptedResponse acceptQuotation(CreateOrderFromAcceptedQuotationRequest request);
    // TODO: Enforce versioning, expiry, immutable accepted version, and accept idempotency.
}
