package mss.mooncake.identity.controller;

import io.swagger.v3.oas.annotations.Hidden;
import mss.mooncake.identity.dto.response.SupplierSummaryResponse;
import mss.mooncake.identity.service.SupplierApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/supplier")
@Hidden
public class SupplierLookupController {

    private final SupplierApplicationService supplierService;

    public SupplierLookupController(SupplierApplicationService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping("/{id}/summary")
    public ResponseEntity<SupplierSummaryResponse> getSummary(@PathVariable("id") Long id) {
        return ResponseEntity.ok(supplierService.getSupplierSummary(id));
    }

    @GetMapping("/by-user/{userId}/summary")
    public ResponseEntity<SupplierSummaryResponse> getSummaryByUserId(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(supplierService.getSupplierSummaryByUserId(userId));
    }
}
