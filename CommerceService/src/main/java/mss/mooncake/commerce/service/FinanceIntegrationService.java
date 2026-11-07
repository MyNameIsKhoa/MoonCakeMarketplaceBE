package mss.mooncake.commerce.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import mss.mooncake.commerce.dto.internal.FinanceOrderContext;

public interface FinanceIntegrationService {
    static Instant remainingDue(LocalDate deliveryDate) {
        return deliveryDate == null ? null
                : deliveryDate.minusDays(3).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
    }

    void syncOrder(FinanceOrderContext input);
    void syncFulfillment(long sellerOrderId, long version, boolean delivered, boolean acceptedByBuyer);
    void syncCollectedPayment(long sellerOrderId);
    void syncCustomerOrder(long sellerOrderId);
}
