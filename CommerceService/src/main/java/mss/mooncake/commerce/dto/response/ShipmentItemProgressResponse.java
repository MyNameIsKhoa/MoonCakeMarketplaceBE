package mss.mooncake.commerce.dto.response;

/**
 * Fulfillment progress of one order item across the non-cancelled shipments of its seller order.
 * allocated = in any non-cancelled shipment; shipped = in SHIPPED or DELIVERED shipments; delivered = in DELIVERED
 * shipments; remaining = ordered - allocated (what a new shipment may still carry). Legacy shipments without lines
 * are not counted.
 */
public record ShipmentItemProgressResponse(
        Long orderItemId,
        String productNameSnapshot,
        String variantNameSnapshot,
        String skuSnapshot,
        int orderedQuantity,
        int allocatedQuantity,
        int shippedQuantity,
        int deliveredQuantity,
        int remainingQuantity
) {}
