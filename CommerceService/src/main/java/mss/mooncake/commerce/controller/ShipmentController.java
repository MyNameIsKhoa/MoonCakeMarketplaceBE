package mss.mooncake.commerce.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import mss.mooncake.commerce.dto.request.CreateShipmentRequest;
import mss.mooncake.commerce.dto.request.UpdateShipmentRequest;
import mss.mooncake.commerce.dto.response.ShipmentResponse;
import mss.mooncake.commerce.security.CommercePrincipal;
import mss.mooncake.commerce.service.FulfillmentApplicationService;
import mss.mooncake.commerce.service.FinanceActorResolver;
import mss.mooncake.commerce.exception.ForbiddenException;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
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
    private final FinanceActorResolver actors;

    public ShipmentController(FulfillmentApplicationService fulfillmentService, FinanceActorResolver actors) {
        this.fulfillmentService = fulfillmentService;
        this.actors = actors;
    }

    private Long supplier(Authentication authentication) {
        Long id = actors.resolve(authentication).supplierId();
        if (id == null) throw new ForbiddenException("Verified supplier required");
        return id;
    }

    @PostMapping
    public ResponseEntity<ShipmentResponse> createShipment(
            @Valid @RequestBody CreateShipmentRequest request,
            Authentication authentication) {
        Long supplierId = supplier(authentication);
        ShipmentResponse response = fulfillmentService.createShipment(request, supplierId);
        return ResponseEntity.created(URI.create("/api/shipments/" + response.shipmentId())).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ShipmentResponse>> getShipments(
            @RequestParam Long sellerOrderId,
            Authentication authentication) {
        return ResponseEntity.ok(fulfillmentService.getShipmentsBySellerOrder(sellerOrderId, actors.resolve(authentication)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShipmentResponse> getShipmentById(
            @PathVariable Long id,
            Authentication authentication) {
        return ResponseEntity.ok(fulfillmentService.getShipmentById(id, actors.resolve(authentication)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ShipmentResponse> updateShipment(
            @PathVariable Long id,
            @RequestBody UpdateShipmentRequest request,
            Authentication authentication) {
        Long supplierId = supplier(authentication);
        return ResponseEntity.ok(fulfillmentService.updateShipment(id, request, supplierId));
    }

    @PostMapping("/{id}/ship")
    public ResponseEntity<ShipmentResponse> markAsShipped(
            @PathVariable Long id,
            Authentication authentication) {
        Long supplierId = supplier(authentication);
        return ResponseEntity.ok(fulfillmentService.markAsShipped(id, supplierId));
    }

    @PostMapping("/{id}/delivered")
    public ResponseEntity<ShipmentResponse> markAsDelivered(
            @PathVariable Long id,
            Authentication authentication) {
        var actor = actors.resolve(authentication);
        return ResponseEntity.ok(fulfillmentService.markAsDelivered(id, actor.supplierId(), actor.admin()));
    }
}
