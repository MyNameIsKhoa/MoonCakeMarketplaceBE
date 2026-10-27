package mss.mooncake.commerce.service;

import java.util.List;
import mss.mooncake.commerce.dto.request.CreateReviewRequest;
import mss.mooncake.commerce.dto.request.CreateShipmentRequest;
import mss.mooncake.commerce.dto.request.UpdateShipmentRequest;
import mss.mooncake.commerce.dto.response.ReviewResponse;
import mss.mooncake.commerce.dto.response.SellerOrderAcceptanceResponse;
import mss.mooncake.commerce.dto.response.ShipmentResponse;

public interface FulfillmentApplicationService {

    // --- Shipment Operations ---
    // userId is the JWT user id; the supplier owner is resolved through Identity (seller orders store supplierId).
    ShipmentResponse createShipment(CreateShipmentRequest request, Long userId);

    ShipmentResponse updateShipment(Long shipmentId, UpdateShipmentRequest request, Long userId);

    ShipmentResponse markAsShipped(Long shipmentId, Long userId);

    ShipmentResponse markAsDelivered(Long shipmentId, Long userId, boolean isAdmin);

    List<ShipmentResponse> getShipmentsBySellerOrder(Long sellerOrderId, Long userId, Long customerId, boolean isAdmin);

    ShipmentResponse getShipmentById(Long shipmentId, Long userId, Long customerId, boolean isAdmin);

    // --- Order Acceptance Operations ---
    SellerOrderAcceptanceResponse acceptSellerOrder(Long sellerOrderId, Long customerId);

    // --- Review Operations ---
    ReviewResponse createReview(CreateReviewRequest request, Long customerId);

    List<ReviewResponse> getReviewsByVariant(Long variantId);

    List<ReviewResponse> getReviewsByStore(Long storeId);

    List<ReviewResponse> getMyReviews(Long customerId);

    ReviewResponse getReviewById(Long reviewId);

    // --- Internal Checks (For TV5 Settlement & TV3) ---
    void assertEligibleForShipment(Long sellerOrderId);

    boolean isEligibleForSettlement(Long sellerOrderId);
}
