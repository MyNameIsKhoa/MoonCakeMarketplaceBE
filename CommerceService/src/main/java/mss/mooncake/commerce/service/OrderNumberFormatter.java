package mss.mooncake.commerce.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** Public order numbers are deterministic; internal user IDs are not exposed. */
@Component
public class OrderNumberFormatter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public String customerOrderNo(LocalDate placedDateUtc, long orderId) {
        if (orderId <= 0) throw new IllegalArgumentException("orderId must be positive");
        return "MSS-" + DATE.format(placedDateUtc) + "-" + orderId;
    }

    public String sellerOrderNo(LocalDate placedDateUtc, long orderId, long sellerOrderId) {
        if (sellerOrderId <= 0) throw new IllegalArgumentException("sellerOrderId must be positive");
        return customerOrderNo(placedDateUtc, orderId) + "-" + sellerOrderId;
    }
}
