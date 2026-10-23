package mss.mooncake.commerce.service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import mss.mooncake.commerce.dto.internal.FinanceOrderContext;
import mss.mooncake.commerce.exception.FinanceException;
import mss.mooncake.commerce.repository.FinanceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local module port: TV3 and TV6 belong to Commerce, so they call this Java service,
 * not HTTP or another service's database. No public endpoint can forge acceptance/delivery. */
@Service
public class FinanceIntegrationService {
    private final FinanceRepository repository;

    public FinanceIntegrationService(FinanceRepository repository) { this.repository = repository; }

    public static Instant remainingDue(java.time.LocalDate deliveryDate) {
        return deliveryDate == null ? null
                : deliveryDate.minusDays(3).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
    }

    @Transactional
    public void syncOrder(FinanceOrderContext input) {
        if (input.totalAmount() <= 0 || input.sourceVersion() <= 0 || input.customerId() <= 0
                || input.supplierId() <= 0 || (input.depositAmount() != null
                && (input.depositAmount() <= 0 || input.depositAmount() >= input.totalAmount()))) {
            throw new FinanceException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_CONTEXT");
        }
        // Serialize initial registration and subsequent snapshots with TV3's persisted local order.
        repository.update("UPDATE seller_order SET status=status WHERE seller_order_id=?", input.sellerOrderId());
        if (!repository.matchesPersistedOrder(input)) {
            throw new FinanceException(HttpStatus.CONFLICT, "CONTEXT_DOES_NOT_MATCH_ORDER");
        }
        if (repository.hasContext(input.sellerOrderId())) {
            var old = repository.lock(input.sellerOrderId());
            if (input.sourceVersion() < old.sourceVersion()) { return; }
            if (input.sourceVersion() == old.sourceVersion()) {
                if (old.customerId() != input.customerId() || old.supplierId() != input.supplierId()
                        || old.total() != input.totalAmount() || !Objects.equals(old.deposit(), input.depositAmount())
                        || !Objects.equals(old.deliveryDate(), input.deliveryDate()) || old.accepted() != input.accepted()) {
                    throw new FinanceException(HttpStatus.CONFLICT, "CONTEXT_VERSION_CONFLICT");
                }
                return;
            }
            boolean moneyChanged = old.total() != input.totalAmount() || !Objects.equals(old.deposit(), input.depositAmount());
            if (old.customerId() != input.customerId() || old.supplierId() != input.supplierId()
                    || (old.accepted() && !input.accepted()) || moneyChanged
                    || (repository.active(old.id()) && !Objects.equals(old.deliveryDate(), input.deliveryDate()))) {
                throw new FinanceException(HttpStatus.CONFLICT, "RECONCILE_EXISTING_OBLIGATIONS_FIRST");
            }
            repository.update("""
                    UPDATE finance_context SET delivery_date=?,accepted=?,source_version=? WHERE seller_order_id=?
                    """, input.deliveryDate(), input.accepted(), input.sourceVersion(), input.sellerOrderId());
        } else {
            repository.update("""
                    INSERT INTO finance_context(seller_order_id,customer_id,supplier_id,total_amount,deposit_amount,
                    delivery_date,accepted,source_version) VALUES(?,?,?,?,?,?,?,?)
                    """, input.sellerOrderId(), input.customerId(), input.supplierId(), input.totalAmount(),
                    input.depositAmount(), input.deliveryDate(), input.accepted(), input.sourceVersion());
        }
        if (input.accepted() && repository.payments(input.sellerOrderId()).isEmpty()) {
            if (input.depositAmount() == null) {
                obligation(input, "FULL", input.totalAmount(), null);
            } else {
                obligation(input, "DEPOSIT", input.depositAmount(), null);
                obligation(input, "REMAINING", input.totalAmount() - input.depositAmount(), remainingDue(input.deliveryDate()));
            }
        }
        repository.update("""
                UPDATE payment SET due_at=?,context_version=? WHERE seller_order_id=? AND payment_type='REMAINING'
                AND order_code IS NULL AND status='PENDING'
                """, FinanceRepository.utc(remainingDue(input.deliveryDate())), input.sourceVersion(), input.sellerOrderId());
        repository.event(input.sellerOrderId(), "PAYMENT_CONTEXT_UPDATED", input.sellerOrderId(), "TV3", "version=" + input.sourceVersion());
    }

    private void obligation(FinanceOrderContext c, String type, long amount, Instant dueAt) {
        repository.update("""
                INSERT INTO payment(seller_order_id,payment_type,amount,due_at,provider,status,context_version)
                VALUES(?,?,?,?,'PAYOS','PENDING',?)
                """, c.sellerOrderId(), type, amount, FinanceRepository.utc(dueAt), c.sourceVersion());
    }

    /** TV6 supplies a monotonically increasing snapshot version; duplicates/old events are harmless. */
    @Transactional
    public void syncFulfillment(long sellerOrderId, long version, boolean delivered, boolean acceptedByBuyer) {
        var context = repository.lock(sellerOrderId);
        if (version < context.fulfillmentVersion()) { return; }
        if (version == context.fulfillmentVersion()) {
            if (context.delivered() != delivered || context.received() != acceptedByBuyer) {
                throw new FinanceException(HttpStatus.CONFLICT, "FULFILLMENT_VERSION_CONFLICT");
            }
            return;
        }
        if ((acceptedByBuyer && !delivered) || (context.delivered() && !delivered)
                || (context.received() && !acceptedByBuyer)) {
            throw new FinanceException(HttpStatus.CONFLICT, "INVALID_FULFILLMENT_TRANSITION");
        }
        repository.update("""
                UPDATE finance_context SET fulfillment_version=?,delivered=?,received=? WHERE seller_order_id=?
                """, version, delivered, acceptedByBuyer, sellerOrderId);
        repository.event(sellerOrderId, "FULFILLMENT_CONTEXT_UPDATED", sellerOrderId, "TV6", "version=" + version);
    }
}
