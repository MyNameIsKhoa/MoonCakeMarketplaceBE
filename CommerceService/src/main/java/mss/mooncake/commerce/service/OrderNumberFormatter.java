package mss.mooncake.commerce.service;

import java.time.LocalDate;

public interface OrderNumberFormatter {
    String customerOrderNo(LocalDate placedDateUtc, long orderId);
    String sellerOrderNo(LocalDate placedDateUtc, long orderId, long sellerOrderId);
}
