package mss.mooncake.rfqnegotiation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import mss.mooncake.rfqnegotiation.dto.request.CreateQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.request.QuotationDecisionRequest;
import mss.mooncake.rfqnegotiation.dto.request.ReviseQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.PageResponse;
import mss.mooncake.rfqnegotiation.dto.response.QuotationResponse;
import mss.mooncake.rfqnegotiation.dto.response.QuotationSummaryResponse;
import mss.mooncake.rfqnegotiation.enums.QuotationStatus;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.service.QuotationManagementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "RFQ quotations", description = "TV4 quotation versions and supplier dashboard")
@SecurityRequirement(name = "Bearer Authentication")
public class QuotationManagementController {
    private final QuotationManagementService service;

    public QuotationManagementController(QuotationManagementService service) { this.service = service; }

    @PostMapping("/api/rfqs/{rfqId}/quotations")
    @Operation(operationId = "rfqCreateQuotation", summary = "Submit a quotation; prices and version are checked by the server")
    public ResponseEntity<QuotationResponse> create(@PathVariable Long rfqId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateQuotationRequest request) {
        QuotationResponse result = service.create(rfqId, key, request);
        return ResponseEntity.created(URI.create("/api/quotations/" + result.quotationId())).body(result);
    }

    @GetMapping("/api/rfqs/{rfqId}/quotations")
    @Operation(operationId = "rfqListQuotations", summary = "Buyer compares RFQ quotations; suppliers see their own")
    public PageResponse<QuotationSummaryResponse> listForRfq(@PathVariable Long rfqId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return service.listForRfq(rfqId, page, size, sort);
    }

    @GetMapping("/api/quotations")
    @Operation(operationId = "rfqListMyQuotations", summary = "List the authenticated supplier's quotations")
    public PageResponse<QuotationSummaryResponse> listMine(@RequestParam(defaultValue = "true") boolean mine,
            @RequestParam(required = false) Long rfqId, @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) QuotationStatus status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String sort) {
        if (!mine) throw ApiException.badRequest("SUPPLIER_SCOPE_REQUIRED", "Danh sách báo giá chỉ hỗ trợ mine=true");
        return service.listMine(rfqId, storeId, status, page, size, sort);
    }

    @GetMapping("/api/quotations/{quotationId}")
    @Operation(operationId = "rfqGetQuotation", summary = "Read an immutable quotation version as buyer or supplier owner")
    public QuotationResponse get(@PathVariable Long quotationId) { return service.get(quotationId); }

    @PostMapping("/api/quotations/{quotationId}/revisions")
    @Operation(operationId = "rfqReviseQuotation", summary = "Create a new submitted version of the latest quotation")
    public ResponseEntity<QuotationResponse> revise(@PathVariable Long quotationId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ReviseQuotationRequest request) {
        QuotationResponse result = service.revise(quotationId, key, request);
        return ResponseEntity.created(URI.create("/api/quotations/" + result.quotationId())).body(result);
    }

    @PostMapping("/api/quotations/{quotationId}/reject")
    @Operation(operationId = "rfqRejectQuotation", summary = "Buyer rejects a submitted quotation; replay with the same reason is safe")
    public QuotationResponse reject(@PathVariable Long quotationId,
            @Valid @RequestBody(required = false) QuotationDecisionRequest request) {
        return service.reject(quotationId, request);
    }

    @PostMapping("/api/quotations/{quotationId}/cancel")
    @Operation(operationId = "rfqCancelQuotation", summary = "Supplier withdraws a submitted quotation before conversion begins")
    public QuotationResponse cancel(@PathVariable Long quotationId,
            @Valid @RequestBody(required = false) QuotationDecisionRequest request) {
        return service.cancel(quotationId, request);
    }
}
