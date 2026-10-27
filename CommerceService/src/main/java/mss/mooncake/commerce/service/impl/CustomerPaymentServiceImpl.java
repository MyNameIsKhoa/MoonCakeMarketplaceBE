package mss.mooncake.commerce.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mss.mooncake.commerce.client.PaymentProvider;
import mss.mooncake.commerce.client.CatalogServiceClient;
import mss.mooncake.commerce.dto.response.CustomerPaymentResponse;
import mss.mooncake.commerce.exception.FinanceException;
import mss.mooncake.commerce.repository.FinanceRepository;
import mss.mooncake.commerce.repository.FinanceRepository.CustomerPayment;
import mss.mooncake.commerce.repository.FinanceRepository.CustomerPaymentItem;
import mss.mooncake.commerce.service.CustomerPaymentService;
import mss.mooncake.commerce.service.FinanceService;
import mss.mooncake.commerce.service.PaymentDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CustomerPaymentServiceImpl implements CustomerPaymentService {

    private static final Logger log = LoggerFactory.getLogger(CustomerPaymentServiceImpl.class);
    private final FinanceRepository repository;
    private final PaymentProvider provider;
    private final TransactionTemplate transaction;
    private final CatalogServiceClient catalog;
    private final ObjectProvider<FinanceService> finance;

    @Autowired
    public CustomerPaymentServiceImpl(FinanceRepository repository, PaymentProvider provider,
            PlatformTransactionManager manager, CatalogServiceClient catalog,
            ObjectProvider<FinanceService> finance) {
        this.repository = repository;
        this.provider = provider;
        this.transaction = new TransactionTemplate(manager);
        this.catalog = catalog;
        this.finance = finance;
    }

    public CustomerPaymentServiceImpl(FinanceRepository repository, PaymentProvider provider,
            PlatformTransactionManager manager) {
        this(repository, provider, manager, null, null);
    }

    private static void require(boolean value, String code) {
        if (!value) { throw new FinanceException(HttpStatus.CONFLICT, code); }
    }

    private List<Long> lockOrder(long orderId) {
        repository.lockCustomerOrder(orderId);
        var ids = repository.sellerOrderIds(orderId);
        require(!ids.isEmpty(), "PAYMENT_CONTEXT_NOT_READY");
        ids.forEach(repository::lock);
        return ids;
    }

    private void buyer(long orderId, FinanceService.Actor actor) {
        if (actor.customerId() == null || actor.customerId() != repository.customerOrderOwner(orderId)) {
            throw new FinanceException(HttpStatus.FORBIDDEN, "FINANCE_ACCESS_DENIED");
        }
    }

    private CustomerPaymentResponse summaryLocked(long orderId, List<Long> ids) {
        var latest = repository.customerPayments(orderId).stream().findFirst().orElse(null);
        long paid = ids.stream().mapToLong(repository::paid).sum();
        var payment = latest == null ? null : new CustomerPaymentResponse.Payment(latest.id(), latest.orderId(),
                latest.orderCode(), latest.amount(), latest.status(), latest.linkId(), latest.checkoutUrl(),
                latest.qrCode(), latest.expiresAt());
        var allocations = latest == null ? List.<CustomerPaymentResponse.Allocation>of()
                : repository.customerPaymentItems(latest.id()).stream()
                        .map(i -> new CustomerPaymentResponse.Allocation(i.paymentId(), i.sellerOrderId(),
                                i.amount(), i.sellerOrderNo())).toList();
        return new CustomerPaymentResponse(orderId, repository.customerOrderTotal(orderId), paid, payment, allocations);
    }

    public CustomerPaymentResponse summary(long orderId, FinanceService.Actor actor) {
        return transaction.execute(status -> {
            var ids = lockOrder(orderId);
            buyer(orderId, actor);
            return summaryLocked(orderId, ids);
        });
    }

    public CustomerPaymentResponse initiate(long orderId, String key, FinanceService.Actor actor) {
        if (key == null || !key.matches("[A-Za-z0-9:_-]{8,100}")) {
            throw new FinanceException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        }
        CustomerPayment reserved = transaction.execute(status -> {
            var ids = lockOrder(orderId);
            buyer(orderId, actor);
            require(repository.retailOrder(orderId), "CUSTOMER_PAYMENT_RETAIL_ONLY");
            var existing = repository.customerPayments(orderId);
            var replay = existing.stream().filter(p -> key.equals(p.key())).findFirst();
            if (replay.isPresent()) { return replay.orElseThrow(); }
            require(repository.count("SELECT COUNT(*) FROM customer_payment WHERE idempotency_key=?", key) == 0,
                    "IDEMPOTENCY_KEY_CONFLICT");
            require(existing.stream().noneMatch(p -> List.of("CREATING", "RECONCILING", "PENDING", "CANCELLING")
                    .contains(p.status())), "ACTIVE_PAYMENT_EXISTS");
            require(ids.stream().mapToLong(repository::paid).sum() == 0, "FULL_PAYMENT_ALREADY_STARTED");
            List<CustomerPaymentItem> items = new ArrayList<>();
            long amount = 0;
            for (long id : ids) {
                var context = repository.context(id);
                require(context.accepted(), "ORDER_NOT_ACCEPTED");
                require(context.deposit() == null, "CUSTOMER_PAYMENT_RETAIL_ONLY");
                require(!repository.active(id), "ACTIVE_PAYMENT_EXISTS");
                require(repository.cancellations(id).stream().noneMatch(c -> !"REJECTED".equals(c.status()))
                        && repository.refunds(id).stream().noneMatch(c -> !"REJECTED".equals(c.status()))
                        && repository.disputes(id).stream().noneMatch(c -> "OPEN".equals(c.status())),
                        "PAYMENT_BLOCKED_BY_CASE");
                require(repository.settlements(id).stream().noneMatch(s ->
                        List.of("PROCESSING", "SUCCESS").contains(s.status())), "PAYOUT_ALREADY_RESERVED");
                var full = repository.payments(id).stream().filter(p -> "FULL".equals(p.type())
                        && "PENDING".equals(p.status()) && p.orderCode() == null).findFirst();
                require(full.isPresent(), "PAYMENT_CONTEXT_NOT_READY");
                require(full.orElseThrow().amount() == context.total(), "ORDER_TOTAL_MISMATCH");
                items.add(new CustomerPaymentItem(full.orElseThrow().id(), id, context.total(), ""));
                amount = Math.addExact(amount, context.total());
            }
            require(amount == repository.customerOrderTotal(orderId), "ORDER_TOTAL_MISMATCH");
            long groupId = repository.insert("""
                    INSERT INTO customer_payment(customer_order_id,order_code,idempotency_key,amount,status,expires_at)
                    VALUES(?,?,?,?,'CREATING',?)
                    """, orderId, repository.nextOrderCode(), key, amount,
                    FinanceRepository.utc(Instant.now().plusSeconds(900)));
            for (var item : items) {
                repository.update("""
                        INSERT INTO customer_payment_item(customer_payment_id,payment_id,amount) VALUES(?,?,?)
                        """, groupId, item.paymentId(), item.amount());
            }
            return repository.customerPayment(groupId);
        });
        boolean claimed = Boolean.TRUE.equals(transaction.execute(status -> {
            lockOrder(orderId);
            return repository.update("""
                    UPDATE customer_payment SET status='RECONCILING'
                    WHERE customer_payment_id=? AND status='CREATING'
                    """, reserved.id()) == 1;
        }));
        if (claimed) {
            try {
                var link = provider.create(reserved.orderCode(), reserved.amount(), reserved.expiresAt(),
                        PaymentDescription.customer(repository.customerOrderNumber(orderId), orderId));
                transaction.executeWithoutResult(status -> {
                    lockOrder(orderId);
                    require(link.orderCode() == reserved.orderCode() && link.amount() == reserved.amount()
                            && link.id() != null, "PROVIDER_LINK_MISMATCH");
                    // A reconciliation that ran meanwhile may have settled or cancelled the payment: never revive it.
                    repository.update("""
                            UPDATE customer_payment SET payment_link_id=?,checkout_url=?,qr_code=?,
                            status=CASE WHEN status IN ('RECONCILING','PENDING') THEN 'PENDING' ELSE status END
                            WHERE customer_payment_id=?
                            """, link.id(), link.checkoutUrl(), link.qrCode(), reserved.id());
                });
            } catch (RuntimeException ex) {
                log.warn("PayOS link creation failed for customer payment {} (orderCode {}): {}", reserved.id(),
                        reserved.orderCode(), PaymentProvider.describe(ex), ex);
                // Never create a second remote link blindly: reconcile the same orderCode, which cancels a link
                // PayOS made without its URL reaching us.
                try {
                    return reconcile(orderId, actor, false);
                } catch (RuntimeException unresolved) {
                    log.warn("Customer payment {} stays RECONCILING after the failed create: {}", reserved.id(),
                            unresolved.getMessage());
                }
            }
        }
        return summary(orderId, actor);
    }

    public CustomerPaymentResponse reconcile(long orderId, FinanceService.Actor actor, boolean cancel) {
        var reservation = transaction.execute(status -> {
            lockOrder(orderId);
            buyer(orderId, actor);
            var latest = repository.customerPayments(orderId).stream().findFirst();
            require(latest.isPresent(), "PAYMENT_LINK_NOT_CREATED");
            return latest.orElseThrow();
        });
        if ("SUCCESS".equals(reservation.status())) { return summary(orderId, actor); }
        PaymentProvider.Status fetched;
        try {
            fetched = cancel ? provider.cancel(reservation.orderCode()) : provider.get(reservation.orderCode());
            // PayOS made this link but its URL never reached us, so the buyer cannot open it: cancel it so a
            // new link can be issued. Re-read the row first: a create still in flight may have stored the URL.
            if (!cancel && "PENDING".equals(fetched.status()) && fetched.paid() == 0
                    && repository.customerPayment(reservation.id()).checkoutUrl() == null) {
                fetched = provider.cancel(reservation.orderCode());
            }
        } catch (RuntimeException ex) {
            log.warn("PayOS reconciliation failed for customer payment {} (orderCode {}): {}", reservation.id(),
                    reservation.orderCode(), PaymentProvider.describe(ex), ex);
            throw new FinanceException(HttpStatus.SERVICE_UNAVAILABLE, "PAYOS_RECONCILIATION_UNAVAILABLE");
        }
        var remote = fetched;
        transaction.executeWithoutResult(status -> {
            lockOrder(orderId);
            var current = repository.customerPayment(reservation.id());
            require(remote.orderCode() == current.orderCode() && remote.amount() == current.amount()
                    && (current.linkId() == null || current.linkId().equals(remote.linkId())), "PROVIDER_LINK_MISMATCH");
            if ("PAID".equals(remote.status()) && remote.paid() == current.amount() && remote.reference() != null) {
                collect(current, remote.linkId(), remote.reference(), remote.paid());
            } else if (!"SUCCESS".equals(current.status())) {
                String next = remote.paid() > 0 ? "RECONCILING"
                        : List.of("CANCELLED", "EXPIRED").contains(remote.status()) ? remote.status() : "PENDING";
                repository.update("UPDATE customer_payment SET status=?,payment_link_id=? WHERE customer_payment_id=?",
                        next, remote.linkId(), current.id());
            }
        });
        return summary(orderId, actor);
    }

    public boolean receive(PaymentProvider.Receipt receipt) {
        var found = repository.customerPaymentByCode(receipt.orderCode());
        if (found.isEmpty()) { return false; }
        transaction.executeWithoutResult(status -> {
            var current = found.getFirst();
            lockOrder(current.orderId());
            current = repository.customerPayment(current.id());
            require(receipt.amount() == current.amount() && "VND".equals(receipt.currency())
                    && receipt.linkId() != null
                    && (current.linkId() == null || current.linkId().equals(receipt.linkId())),
                    "WEBHOOK_PAYMENT_MISMATCH");
            collect(current, receipt.linkId(), receipt.reference(), receipt.amount());
        });
        return true;
    }

    @Override
    public void cancelUnpaidOrder(long orderId, String reason, FinanceService.Actor actor) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new FinanceException(HttpStatus.BAD_REQUEST, "CANCELLATION_REASON_REQUIRED");
        }
        boolean pending = Boolean.TRUE.equals(transaction.execute(status -> {
            var ids = lockOrder(orderId);
            buyer(orderId, actor);
            require(repository.retailOrder(orderId), "CUSTOMER_PAYMENT_RETAIL_ONLY");
            if (repository.count("SELECT COUNT(*) FROM customer_order WHERE order_id=? AND status='CANCELLED'",
                    orderId) == 1) { return false; }
            for (long id : ids) {
                require(repository.paid(id) == 0, "ORDER_ALREADY_PAID");
                require(repository.count("""
                        SELECT COUNT(*) FROM seller_order WHERE seller_order_id=?
                        AND status IN ('AWAITING_PAYMENT','PENDING_CONFIRMATION')
                        """, id) == 1, "ORDER_CANNOT_BE_CANCELLED");
                var cases = repository.cancellations(id);
                require(cases.isEmpty() || cases.stream().allMatch(c -> "REQUESTED".equals(c.status())),
                        "CANCELLATION_ALREADY_EXISTS");
                if (cases.isEmpty()) {
                    long cancellationId = repository.insert("""
                            INSERT INTO cancellation(seller_order_id,actor_type,actor_id_ref,reason,status,requested_at)
                            VALUES(?,'CUSTOMER',?,?,'REQUESTED',?)
                            """, id, actor.userId(), reason, FinanceRepository.utc(Instant.now()));
                    repository.event(id, "CANCELLATION_REQUESTED", cancellationId,
                            Long.toString(actor.userId()), reason);
                }
            }
            repository.update("UPDATE customer_order SET status='CANCEL_REQUESTED' WHERE order_id=?", orderId);
            return true;
        }));
        if (!pending) { return; }

        // Once requested, finance rejects new links while remote links and stock are reconciled.
        for (long id : repository.sellerOrderIds(orderId)) {
            for (var payment : repository.payments(id)) {
                if (payment.orderCode() != null && List.of("CREATING", "RECONCILING", "PENDING", "CANCELLING")
                        .contains(payment.status())) {
                    require(finance != null, "PAYOS_RECONCILIATION_UNAVAILABLE");
                    finance.getObject().reconcile(payment.id(), actor, true);
                }
            }
        }
        var group = repository.customerPayments(orderId).stream().findFirst().orElse(null);
        if (group != null && List.of("CREATING", "RECONCILING", "PENDING", "CANCELLING")
                .contains(group.status())) { reconcile(orderId, actor, true); }

        String reservationKey = repository.stockReservationKey(orderId);
        if (reservationKey != null) {
            var restored = catalog == null ? null : catalog.cancelConfirmedStockReservation(reservationKey);
            require(restored != null && "CANCELLED".equals(restored.status()), "STOCK_RESTORE_REQUIRED");
        }
        transaction.executeWithoutResult(status -> {
            var ids = lockOrder(orderId);
            buyer(orderId, actor);
            for (long id : ids) {
                require(repository.paid(id) == 0 && !repository.active(id)
                        && !repository.activeCustomerPayment(id), "PAYMENT_RECONCILIATION_REQUIRED");
                var cancellation = repository.cancellations(id).stream().filter(c -> "REQUESTED".equals(c.status()))
                        .findFirst().orElseThrow(() -> new FinanceException(HttpStatus.CONFLICT,
                                "CANCELLATION_NOT_PENDING"));
                repository.update("""
                        UPDATE cancellation SET status='APPROVED',decision_reason=?,cancelled_at=?
                        WHERE cancellation_id=?
                        """, "Hủy ngay vì đơn chưa thanh toán", FinanceRepository.utc(Instant.now()), cancellation.id());
                repository.update("UPDATE seller_order SET status='CANCELLED' WHERE seller_order_id=?", id);
                repository.event(id, "CANCELLATION_DECIDED", cancellation.id(),
                        Long.toString(actor.userId()), "Unpaid retail order cancelled");
            }
            repository.update("UPDATE customer_order SET status='CANCELLED' WHERE order_id=?", orderId);
        });
    }

    private void collect(CustomerPayment payment, String linkId, String reference, long amount) {
        require(reference != null && !reference.isBlank() && reference.length() <= 255,
                "INVALID_PROVIDER_REFERENCE");
        long duplicate = repository.count("SELECT COUNT(*) FROM customer_payment_webhook_inbox WHERE provider_reference=?",
                reference);
        if (duplicate > 0) {
            require(repository.count("""
                    SELECT COUNT(*) FROM customer_payment_webhook_inbox
                    WHERE provider_reference=? AND customer_payment_id=? AND amount=?
                    """, reference, payment.id(), amount) == 1, "PROVIDER_REFERENCE_CONFLICT");
            return;
        }
        require(repository.count("SELECT COUNT(*) FROM finance_webhook_inbox WHERE provider_reference=?", reference) == 0,
                "PROVIDER_REFERENCE_CONFLICT");
        require(!"SUCCESS".equals(payment.status()), "DUPLICATE_PAYMENT_REVIEW_REQUIRED");
        var items = repository.customerPaymentItems(payment.id());
        require(!items.isEmpty() && items.stream().mapToLong(CustomerPaymentItem::amount).sum() == amount,
                "ORDER_TOTAL_MISMATCH");
        for (var item : items) {
            var child = repository.payment(item.paymentId());
            require("PENDING".equals(child.status()) && child.orderCode() == null
                    && repository.paid(item.sellerOrderId()) + item.amount()
                    <= repository.context(item.sellerOrderId()).total(), "OVERPAYMENT_REVIEW_REQUIRED");
        }
        repository.update("""
                INSERT INTO customer_payment_webhook_inbox(provider_reference,customer_payment_id,amount,received_at)
                VALUES(?,?,?,?)
                """, reference, payment.id(), amount, FinanceRepository.utc(Instant.now()));
        for (var item : items) {
            String childReference = "customer-payment:" + payment.id() + ":" + item.paymentId();
            repository.update("""
                    UPDATE payment SET status='SUCCESS',hold_status='HELD',transaction_ref=?,paid_at=?
                    WHERE payment_id=?
                    """, childReference, FinanceRepository.utc(Instant.now()), item.paymentId());
            repository.ledger(item.sellerOrderId(), "payment:" + item.paymentId(), "HELD", item.amount());
            repository.event(item.sellerOrderId(), "PAYMENT_SUCCESS", item.paymentId(), "PAYOS", "HELD internal ledger");
        }
        repository.update("""
                UPDATE customer_payment SET status='SUCCESS',payment_link_id=?,transaction_ref=?,paid_at=?
                WHERE customer_payment_id=?
                """, linkId, reference, FinanceRepository.utc(Instant.now()), payment.id());
    }
}
