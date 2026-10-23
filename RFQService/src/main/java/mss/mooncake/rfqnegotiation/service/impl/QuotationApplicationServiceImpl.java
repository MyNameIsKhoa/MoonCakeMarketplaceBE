package mss.mooncake.rfqnegotiation.service.impl;
import mss.mooncake.rfqnegotiation.service.QuotationApplicationService;
import mss.mooncake.rfqnegotiation.dto.request.AcceptQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.QuotationAcceptedResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
@Service
public class QuotationApplicationServiceImpl implements QuotationApplicationService {
    @Override public QuotationAcceptedResponse acceptQuotation(Long quotationId, Long customerId,
                                                               AcceptQuotationRequest request) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "RFQ acceptance requires persisted conversion state and Commerce recovery");
    }
}
