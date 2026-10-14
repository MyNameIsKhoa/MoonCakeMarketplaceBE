package mss.mooncake.commerce.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import mss.mooncake.commerce.dto.request.CreateShipmentRequest;
import mss.mooncake.commerce.dto.request.UpdateShipmentRequest;
import mss.mooncake.commerce.dto.response.ShipmentResponse;
import mss.mooncake.commerce.security.UserPrincipal;
import mss.mooncake.commerce.service.FulfillmentApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final FulfillmentApplicationService fulfillmentService;

    public ShipmentController(FulfillmentApplicationService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @PostMapping
    public ResponseEntity<ShipmentResponse> createShipment(
            @Valid @RequestBody CreateShipmentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long supplierId = principal != null ? principal.getUserId() : null;
        ShipmentResponse response = fulfillmentService.createShipment(request, supplierId);
        return ResponseEntity.created(URI.create("/api/shipments/" + response.shipmentId())).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ShipmentResponse>> getShipments(
            @RequestParam Long sellerOrderId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long currentUserId = principal != null ? (principal.getCustomerId() != null ? principal.getCustomerId() : principal.getUserId()) : null;
        boolean isCustomer = principal != null && principal.hasRole("ROLE_CUSTOMER");
        boolean isAdmin = principal != null && principal.hasRole("ROLE_ADMIN");
        return ResponseEntity.ok(fulfillmentService.getShipmentsBySellerOrder(sellerOrderId, currentUserId, isCustomer, isAdmin));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShipmentResponse> getShipmentById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long currentUserId = principal != null ? (principal.getCustomerId() != null ? principal.getCustomerId() : principal.getUserId()) : null;
        boolean isCustomer = principal != null && principal.hasRole("ROLE_CUSTOMER");
        boolean isAdmin = principal != null && principal.hasRole("ROLE_ADMIN");
        return ResponseEntity.ok(fulfillmentService.getShipmentById(id, currentUserId, isCustomer, isAdmin));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ShipmentResponse> updateShipment(
            @PathVariable Long id,
            @RequestBody UpdateShipmentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long supplierId = principal != null ? principal.getUserId() : null;
        return ResponseEntity.ok(fulfillmentService.updateShipment(id, request, supplierId));
    }

    @PostMapping("/{id}/ship")
    public ResponseEntity<ShipmentResponse> markAsShipped(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long supplierId = principal != null ? principal.getUserId() : null;
        return ResponseEntity.ok(fulfillmentService.markAsShipped(id, supplierId));
    }

    @PostMapping("/{id}/delivered")
    public ResponseEntity<ShipmentResponse> markAsDelivered(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long actorId = principal != null ? principal.getUserId() : null;
        boolean isAdmin = principal != null && principal.hasRole("ROLE_ADMIN");
        return ResponseEntity.ok(fulfillmentService.markAsDelivered(id, actorId, isAdmin));
    }
}
