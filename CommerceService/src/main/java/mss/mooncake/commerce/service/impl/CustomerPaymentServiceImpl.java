package mss.mooncake.commerce.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mss.mooncake.commerce.client.PaymentProvider;
import mss.mooncake.commerce.dto.response.CustomerPaymentResponse;
import mss.mooncake.commerce.exception.FinanceException;
import mss.mooncake.commerce.repository.FinanceRepository;
import mss.mooncake.commerce.repository.FinanceRepository.CustomerPayment;
import mss.mooncake.commerce.repository.FinanceRepository.CustomerPaymentItem;
import mss.mooncake.commerce.service.CustomerPaymentService;
import mss.mooncake.commerce.service.FinanceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CustomerPaymentServiceImpl implements CustomerPaymentService {

    private final FinanceRepository repository;
    private final PaymentProvider provider;
    private final TransactionTemplate transaction;

    public CustomerPaymentServiceImpl(FinanceRepository repository, PaymentProvider provider, PlatformTransactionManager manager) {
        this.repository = repository;
        this.provider = provider;
        this.transaction = new TransactionTemplate(manager);
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
                var link = provider.create(reserved.orderCode(), reserved.amount(), reserved.expiresAt());
                transaction.executeWithoutResult(status -> {
                    lockOrder(orderId);
                    require(link.orderCode() == reserved.orderCode() && link.amount() == reserved.amount()
                            && link.id() != null, "PROVIDER_LINK_MISMATCH");
                    repository.update("""
                            UPDATE customer_payment SET payment_link_id=?,checkout_url=?,qr_code=?,
                            status=CASE WHEN status='SUCCESS' THEN status ELSE 'PENDING' END
                            WHERE customer_payment_id=?
                            """, link.id(), link.checkoutUrl(), link.qrCode(), reserved.id());
                });
            } catch (RuntimeException ex) {
                // Keep the reservation for explicit reconciliation; never create a second remote link blindly.
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
        PaymentProvider.Status remote;
        try {
            remote = cancel ? provider.cancel(reservation.orderCode()) : provider.get(reservation.orderCode());
        } catch (RuntimeException ex) {
            throw new FinanceException(HttpStatus.SERVICE_UNAVAILABLE, "PAYOS_RECONCILIATION_UNAVAILABLE");
        }
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
