package mss.mooncake.commerce.service;

public interface FulfillmentApplicationService {
    void assertEligibleForShipment(Long sellerOrderId);
    // TODO: Keep fulfillment, acceptance, and review flows internal to Commerce
    // Service.
}
