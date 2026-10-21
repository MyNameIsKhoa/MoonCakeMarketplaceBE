package mss.mooncake.rfqnegotiation.controller;
import jakarta.validation.Valid;
import mss.mooncake.rfqnegotiation.dto.request.AcceptQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.QuotationAcceptedResponse;
import mss.mooncake.rfqnegotiation.security.RfqPrincipal;
import mss.mooncake.rfqnegotiation.service.QuotationApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/quotations")
public class QuotationController {
    private final QuotationApplicationService quotationApplicationService;
    public QuotationController(QuotationApplicationService quotationApplicationService) { this.quotationApplicationService = quotationApplicationService; }
    @PostMapping("/{quotationId}/accept")
    public ResponseEntity<QuotationAcceptedResponse> accept(@PathVariable Long quotationId,
            @AuthenticationPrincipal RfqPrincipal principal, @Valid @RequestBody AcceptQuotationRequest request) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (principal.customerId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return ResponseEntity.ok(quotationApplicationService.acceptQuotation(quotationId, principal.customerId(), request));
    }
}
