package mss.mooncake.commerce.service;

import java.util.List;
import mss.mooncake.commerce.dto.request.CreateReviewRequest;
import mss.mooncake.commerce.dto.request.CreateShipmentRequest;
import mss.mooncake.commerce.dto.request.UpdateShipmentRequest;
import mss.mooncake.commerce.dto.response.ReviewResponse;
import mss.mooncake.commerce.dto.response.ProductSignalResponse;
import mss.mooncake.commerce.dto.response.SellerOrderAcceptanceResponse;
import mss.mooncake.commerce.dto.response.ShipmentItemProgressResponse;
import mss.mooncake.commerce.dto.response.ShipmentResponse;

public interface FulfillmentApplicationService {

    // --- Shipment Operations ---
    ShipmentResponse createShipment(CreateShipmentRequest request, Long supplierId);

    ShipmentResponse updateShipment(Long shipmentId, UpdateShipmentRequest request, Long supplierId);

    ShipmentResponse markAsShipped(Long shipmentId, Long supplierId);

    /** Supplier withdraws a PLANNED shipment (batch) before it ships. */
    ShipmentResponse cancelShipment(Long shipmentId, Long supplierId);

    ShipmentResponse markAsDelivered(Long shipmentId, Long actorId, boolean isAdmin);

    List<ShipmentResponse> getShipmentsBySellerOrder(Long sellerOrderId, FinanceService.Actor actor);

    ShipmentResponse getShipmentById(Long shipmentId, FinanceService.Actor actor);

    /** Ordered, allocated, shipped, delivered and remaining units of each order item of the seller order. */
    List<ShipmentItemProgressResponse> getShipmentItemProgress(Long sellerOrderId, FinanceService.Actor actor);

    // --- Order Acceptance Operations ---
    SellerOrderAcceptanceResponse acceptSellerOrder(Long sellerOrderId, Long customerId);

    /**
     * System acceptance of a DELIVERED order the buyer never accepted, once its last delivery is at or before
     * {@code deliveredBefore} (dispute window passed) and no dispute was opened since delivery. Returns whether
     * the order was accepted.
     */
    boolean autoAcceptDelivered(Long sellerOrderId, java.time.Instant deliveredBefore);

    // --- Review Operations ---
    ReviewResponse createReview(CreateReviewRequest request, Long customerId);

    List<ReviewResponse> getReviewsByVariant(Long variantId);

    List<ReviewResponse> getReviewsByStore(Long storeId);

    List<ReviewResponse> getMyReviews(Long customerId);

    ReviewResponse getReviewById(Long reviewId);

    List<ProductSignalResponse> getProductSignals(List<Long> variantIds);

    // --- Internal Checks (For TV5 Settlement & TV3) ---
    void assertEligibleForShipment(Long sellerOrderId);

    boolean isEligibleForSettlement(Long sellerOrderId);
}
