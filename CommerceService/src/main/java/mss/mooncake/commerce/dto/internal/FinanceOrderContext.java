package mss.mooncake.commerce.dto.internal;

import java.time.LocalDate;

/** TV3 calls FinanceIntegrationService in the same Commerce transaction, after persisting the order.
 * Amounts are validated integer VND snapshots, never values supplied by the payment frontend.
 * A null deposit means the deposit policy is not yet agreed; FULL remains available after ACCEPT.
 */
public record FinanceOrderContext(long sellerOrderId, long customerId, long supplierId,
        long totalAmount, Long depositAmount, LocalDate deliveryDate, boolean accepted, long sourceVersion) { }
