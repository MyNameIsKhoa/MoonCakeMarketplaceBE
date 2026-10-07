package mss.mooncake.rfqnegotiation.service.impl;
import mss.mooncake.rfqnegotiation.service.QuotationApplicationService;
import mss.mooncake.rfqnegotiation.dto.request.CreateOrderFromAcceptedQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.QuotationAcceptedResponse;
import org.springframework.stereotype.Service;
@Service
public class QuotationApplicationServiceImpl implements QuotationApplicationService {
    @Override public QuotationAcceptedResponse acceptQuotation(CreateOrderFromAcceptedQuotationRequest request) {
        throw new UnsupportedOperationException("TODO: implement quotation acceptance");
    }
}
