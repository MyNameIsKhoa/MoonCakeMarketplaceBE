package mss.mooncake.rfqnegotiation.controller;
import jakarta.validation.Valid;
import mss.mooncake.rfqnegotiation.dto.request.CreateOrderFromAcceptedQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.QuotationAcceptedResponse;
import mss.mooncake.rfqnegotiation.service.QuotationApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/api/quotations")
public class QuotationController {
    private final QuotationApplicationService quotationApplicationService;
    public QuotationController(QuotationApplicationService quotationApplicationService) { this.quotationApplicationService = quotationApplicationService; }
    @PostMapping("/{quotationId}/accept")
    public ResponseEntity<QuotationAcceptedResponse> accept(@Valid @RequestBody CreateOrderFromAcceptedQuotationRequest request) {
        return ResponseEntity.ok(quotationApplicationService.acceptQuotation(request));
    }
}
