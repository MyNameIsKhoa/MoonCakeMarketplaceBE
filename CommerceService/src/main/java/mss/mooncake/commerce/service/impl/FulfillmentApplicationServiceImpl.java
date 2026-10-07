package mss.mooncake.commerce.service.impl;
import mss.mooncake.commerce.service.FulfillmentApplicationService;
import org.springframework.stereotype.Service;
@Service
public class FulfillmentApplicationServiceImpl implements FulfillmentApplicationService {
    @Override public void assertEligibleForShipment(Long sellerOrderId) {
        throw new UnsupportedOperationException("TODO: implement shipment eligibility");
    }
}
