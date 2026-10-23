package mss.mooncake.rfqnegotiation.controller;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import mss.mooncake.rfqnegotiation.dto.request.AcceptQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.QuotationAcceptedResponse;
import mss.mooncake.rfqnegotiation.service.QuotationApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/quotations")
public class QuotationController {
    private final QuotationApplicationService service;
    public QuotationController(QuotationApplicationService service) { this.service = service; }
    @Operation(operationId="acceptQuotation", summary="Chấp nhận phiên bản báo giá và tạo đơn; dùng lại cùng Idempotency-Key khi retry")
    @PostMapping("/{quotationId}/accept")
    public ResponseEntity<QuotationAcceptedResponse> accept(@PathVariable Long quotationId,
            @Valid @RequestBody AcceptQuotationRequest request, @RequestHeader("Idempotency-Key") String key) {
        var result = service.acceptQuotation(quotationId, request, key);
        return ResponseEntity.status("COMPLETED".equals(result.conversionStatus()) ? 200 : 202).body(result);
    }
    @Operation(operationId="getQuotationOrder", summary="Tra cứu trạng thái tạo đơn của báo giá")
    @GetMapping("/{quotationId}/order")
    public QuotationAcceptedResponse order(@PathVariable Long quotationId) { return service.getOrderLink(quotationId); }
}
