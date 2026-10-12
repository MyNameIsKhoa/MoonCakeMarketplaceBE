package mss.mooncake.identity.controller;

import jakarta.validation.Valid;
import mss.mooncake.identity.dto.request.SupplierProfileRequest;
import mss.mooncake.identity.dto.request.UpdateSupplierProfileRequest;
import mss.mooncake.identity.dto.response.SupplierProfileResponse;
import mss.mooncake.identity.exception.UnauthorizedException;
import mss.mooncake.identity.security.UserPrincipal;
import mss.mooncake.identity.service.SupplierApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/supplier-profile")
public class SupplierProfileController {

    private final SupplierApplicationService supplierService;

    public SupplierProfileController(SupplierApplicationService supplierService) {
        this.supplierService = supplierService;
    }

    @PostMapping
    public ResponseEntity<SupplierProfileResponse> registerSupplier(@AuthenticationPrincipal UserPrincipal principal,
                                                                    @Valid @RequestBody SupplierProfileRequest request) {
        if (principal == null) {
            throw new UnauthorizedException("User is not authenticated");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(supplierService.registerSupplier(principal.getUserId(), request));
    }

    @GetMapping
    public ResponseEntity<SupplierProfileResponse> getMySupplierProfile(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("User is not authenticated");
        }
        return ResponseEntity.ok(supplierService.getSupplierProfile(principal.getUserId()));
    }

    @PatchMapping
    public ResponseEntity<SupplierProfileResponse> updateMySupplierProfile(@AuthenticationPrincipal UserPrincipal principal,
                                                                           @Valid @RequestBody UpdateSupplierProfileRequest request) {
        if (principal == null) {
            throw new UnauthorizedException("User is not authenticated");
        }
        return ResponseEntity.ok(supplierService.updateSupplierProfile(principal.getUserId(), request));
    }
}
