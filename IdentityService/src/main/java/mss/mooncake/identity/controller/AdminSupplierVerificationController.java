package mss.mooncake.identity.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import mss.mooncake.identity.dto.request.ReviewSupplierRequest;
import mss.mooncake.identity.dto.response.SupplierProfileResponse;
import mss.mooncake.identity.enums.SupplierVerificationStatus;
import mss.mooncake.identity.service.SupplierApplicationService;
import mss.mooncake.identity.security.UserPrincipal;
import mss.mooncake.identity.dto.response.SupplierVerificationAuditResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/suppliers")
@Tag(name = "Admin - Supplier Verification")
public class AdminSupplierVerificationController {
    private final SupplierApplicationService supplierService;

    public AdminSupplierVerificationController(SupplierApplicationService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    public ResponseEntity<Page<SupplierProfileResponse>> listForReview(
            @RequestParam(defaultValue = "PENDING") SupplierVerificationStatus verificationStatus,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(supplierService.getSupplierProfilesByStatus(verificationStatus, pageable));
    }

    @PostMapping("/{supplierId}/verification")
    public ResponseEntity<SupplierProfileResponse> review(
            @PathVariable Long supplierId,
            @Valid @RequestBody ReviewSupplierRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(supplierService.reviewSupplier(supplierId, request.decision(),
                principal.getUserId(), request.reason().trim()));
    }

    @GetMapping("/{supplierId}/verification-history")
    public ResponseEntity<List<SupplierVerificationAuditResponse>> history(@PathVariable Long supplierId) {
        return ResponseEntity.ok(supplierService.getVerificationHistory(supplierId));
    }
}
