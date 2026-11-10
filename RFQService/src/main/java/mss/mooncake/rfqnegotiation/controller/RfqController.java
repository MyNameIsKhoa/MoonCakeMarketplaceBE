package mss.mooncake.rfqnegotiation.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import mss.mooncake.rfqnegotiation.dto.request.CreateRfqRequest;
import mss.mooncake.rfqnegotiation.dto.response.PageResponse;
import mss.mooncake.rfqnegotiation.dto.response.RfqResponse;
import mss.mooncake.rfqnegotiation.enums.RfqStatus;
import mss.mooncake.rfqnegotiation.service.RfqApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rfqs")
@Tag(name = "RFQs", description = "TV4: authenticated supplier feed and customer-owned RFQs")
public class RfqController {
    private final RfqApplicationService service;

    public RfqController(RfqApplicationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "rfqListRfqs", summary = "Search published RFQs or the customer's RFQs",
            description = "Requires authentication. Default feed is supplier-only, including unverified suppliers (read-only). "
                    + "mine=true requires a customer profile without a supplier profile and returns only that customer's RFQs.")
    public PageResponse<RfqResponse> list(@RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "false") boolean mine,
                                         @RequestParam(required = false) RfqStatus status,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) String sort) {
        return service.list(q, mine, status, page, size, sort);
    }

    @GetMapping("/{rfqId}")
    @Operation(operationId = "rfqGetRfq", summary = "Read an owned RFQ or an eligible supplier RFQ",
            description = "Requires authentication. Supplier mode wins for dual-profile accounts. Suppliers see published public "
                    + "or invited RFQs with private fields hidden; customers see only their own RFQs.")
    public RfqResponse get(@PathVariable Long rfqId) {
        return service.get(rfqId);
    }

    @PostMapping
    @ApiResponse(responseCode = "201", description = "RFQ created and published; Location identifies the new resource")
    @Operation(operationId = "rfqCreateRfq", summary = "Create and immediately publish an RFQ")
    public ResponseEntity<RfqResponse> create(@Valid @RequestBody CreateRfqRequest request) {
        RfqResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/rfqs/" + created.rfqId())).body(created);
    }

    @PatchMapping(value = "/{rfqId}", consumes = "application/merge-patch+json")
    @Operation(operationId = "rfqUpdateRfq", summary = "Edit an open RFQ before any quotation is created",
            description = "Absent fields stay unchanged; null clears nullable fields. customerId, status and IDs cannot be patched.")
    public RfqResponse update(@PathVariable Long rfqId, @RequestBody Map<String, Object> patch) {
        return service.update(rfqId, patch);
    }

    @PostMapping("/{rfqId}/close")
    @Operation(operationId = "rfqCloseRfq", summary = "Close quotation intake without changing existing orders")
    public RfqResponse close(@PathVariable Long rfqId) {
        return service.close(rfqId);
    }

    @PostMapping("/{rfqId}/cancel")
    @Operation(operationId = "rfqCancelRfq", summary = "Cancel the RFQ without cancelling orders already created")
    public RfqResponse cancel(@PathVariable Long rfqId) {
        return service.cancel(rfqId);
    }
}
