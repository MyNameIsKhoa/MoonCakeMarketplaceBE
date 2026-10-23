package mss.mooncake.rfqnegotiation.service;

import mss.mooncake.rfqnegotiation.dto.request.CreateQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.request.QuotationDecisionRequest;
import mss.mooncake.rfqnegotiation.dto.request.ReviseQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.PageResponse;
import mss.mooncake.rfqnegotiation.dto.response.QuotationResponse;
import mss.mooncake.rfqnegotiation.dto.response.QuotationSummaryResponse;
import mss.mooncake.rfqnegotiation.enums.QuotationStatus;

public interface QuotationManagementService {
    QuotationResponse create(Long rfqId, String idempotencyKey, CreateQuotationRequest request);
    QuotationResponse revise(Long quotationId, String idempotencyKey, ReviseQuotationRequest request);
    PageResponse<QuotationSummaryResponse> listForRfq(Long rfqId, int page, int size, String sort);
    PageResponse<QuotationSummaryResponse> listMine(Long rfqId, Long storeId, QuotationStatus status,
                                                 int page, int size, String sort);
    QuotationResponse get(Long quotationId);
    QuotationResponse reject(Long quotationId, QuotationDecisionRequest request);
    QuotationResponse cancel(Long quotationId, QuotationDecisionRequest request);
}
