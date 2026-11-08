package mss.mooncake.commerce.service.impl;

import java.time.Duration;
import java.time.Instant;
import mss.mooncake.commerce.repository.FinanceRepository;
import mss.mooncake.commerce.service.FulfillmentApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A delivered order the buyer neither accepted nor disputed within the seven-day dispute window is accepted
 * by the system, so its settlement is not blocked forever. Each order is accepted in its own transaction.
 */
@Component
public class OrderAutoAcceptanceJob {
    private static final Logger log = LoggerFactory.getLogger(OrderAutoAcceptanceJob.class);
    /** Same seven days the finance module gives the buyer to dispute after the last delivery. */
    private static final Duration DISPUTE_WINDOW = Duration.ofDays(7);
    private final FinanceRepository repository;
    private final FulfillmentApplicationService fulfillment;

    public OrderAutoAcceptanceJob(FinanceRepository repository, FulfillmentApplicationService fulfillment) {
        this.repository = repository;
        this.fulfillment = fulfillment;
    }

    @Scheduled(cron = "${commerce.fulfillment.auto-accept-cron:0 */10 * * * *}")
    public void acceptOverdueDeliveries() {
        Instant cutoff = Instant.now().minus(DISPUTE_WINDOW);
        for (long sellerOrderId : repository.autoAcceptableSellerOrderIds(cutoff)) {
            try {
                fulfillment.autoAcceptDelivered(sellerOrderId, cutoff);
            } catch (RuntimeException failure) {
                log.warn("Seller order {} was not auto-accepted in this run: {}", sellerOrderId, failure.getMessage());
            }
        }
    }
}
