package mss.mooncake.commerce.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mss.mooncake.commerce.client.PaymentProvider;
import mss.mooncake.commerce.dto.response.FinanceResponse;
import mss.mooncake.commerce.exception.FinanceException;
import mss.mooncake.commerce.repository.FinanceRepository;
import mss.mooncake.commerce.repository.FinanceRepository.Context;
import mss.mooncake.commerce.repository.FinanceRepository.Payment;
import mss.mooncake.commerce.service.FinanceService;
import mss.mooncake.commerce.service.FinanceIntegrationService;
import mss.mooncake.commerce.util.PaymentDescription;
import mss.mooncake.commerce.service.CustomerPaymentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.payos.model.webhooks.Webhook;

@Service
public class FinanceServiceImpl implements FinanceService {
    private final FinanceRepository repository;
    private final PaymentProvider provider;
    private final TransactionTemplate transaction;
    private final String feeBasis;
    private final String rounding;
    private final boolean adjudicationEnabled;
    private final CustomerPaymentService customerPayments;

    @Autowired
    public FinanceServiceImpl(FinanceRepository repository, PaymentProvider provider,
            PlatformTransactionManager manager,
            @Value("${finance.settlement.fee-basis:}") String feeBasis,
            @Value("${finance.settlement.rounding:}") String rounding,
            @Value("${finance.dispute.admin-adjudication-enabled:false}") boolean adjudicationEnabled,
            CustomerPaymentService customerPayments) {
        this.repository = repository;
        this.provider = provider;
        this.transaction = new TransactionTemplate(manager);
        this.feeBasis = feeBasis;
        this.rounding = rounding;
        this.adjudicationEnabled = adjudicationEnabled;
        this.customerPayments = customerPayments;
    }

    // Direct construction remains useful for the isolated finance unit tests.
    public FinanceServiceImpl(FinanceRepository repository, PaymentProvider provider,
            PlatformTransactionManager manager, String feeBasis, String rounding, boolean adjudicationEnabled) {
        this(repository, provider, manager, feeBasis, rounding, adjudicationEnabled, null);
    }

    private static void require(boolean condition, String code) {
        if (!condition) { throw new FinanceException(HttpStatus.CONFLICT, code); }
    }

    private void access(Context context, Actor actor, boolean buyerOnly) {
        boolean allowed = buyerOnly ? isBuyer(context, actor)
                : actor.admin() || isBuyer(context, actor)
                        || (actor.supplierId() != null && actor.supplierId() == context.supplierId());
        if (!allowed) { throw new FinanceException(HttpStatus.FORBIDDEN, "FINANCE_ACCESS_DENIED"); }
    }

    private boolean isBuyer(Context context, Actor actor) {
        return actor.customerId() != null && actor.customerId() == context.customerId();
    }

    private void admin(Actor actor) {
        if (!actor.admin()) { throw new FinanceException(HttpStatus.FORBIDDEN, "FINANCE_ADMIN_REQUIRED"); }
    }

    private FinanceResponse.Payment paymentResponse(Payment payment, Context context, Actor actor) {
        if (isBuyer(context, actor)) { return payment.response(); }
        return new FinanceResponse.Payment(payment.id(), payment.orderId(), payment.type(), payment.amount(),
                payment.status(), payment.hold(), payment.dueAt(), payment.expiresAt(), null, null);
    }

    public FinanceResponse summary(long orderId, Actor actor) {
        return transaction.execute(status -> {
            var context = repository.lock(orderId);
            access(context, actor, false);
            return new FinanceResponse(new FinanceResponse.Context(orderId, context.total(), context.deposit(),
                    context.deliveryDate(), context.accepted(), repository.paid(orderId),
                    FinanceIntegrationService.remainingDue(context.deliveryDate())),
                    repository.payments(orderId).stream().map(p -> paymentResponse(p, context, actor)).toList(),
                    repository.cancellations(orderId), repository.refunds(orderId), repository.disputes(orderId),
                    repository.settlements(orderId), eligibility(context), repository.history(orderId));
        });
    }

    private List<String> financialBlocks(long id) {
        List<String> blocks = new ArrayList<>();
        if (repository.disputes(id).stream().anyMatch(c -> "OPEN".equals(c.status()))) { blocks.add("OPEN_DISPUTE"); }
        if (repository.cancellations(id).stream().anyMatch(c -> !"REJECTED".equals(c.status()))) { blocks.add("CANCELLATION"); }
        if (repository.refunds(id).stream().anyMatch(c -> !"REJECTED".equals(c.status()))) { blocks.add("REFUND"); }
        return blocks;
    }

    private FinanceResponse.Eligibility eligibility(Context c) {
        var blocks = financialBlocks(c.id());
        if (!c.accepted()) { blocks.add("ORDER_NOT_ACCEPTED"); }
        if (repository.paid(c.id()) != c.total()) { blocks.add("NOT_FULLY_PAID"); }
        if (repository.active(c.id())) { blocks.add("PAYMENT_RECONCILIATION_REQUIRED"); }
        if (!c.delivered() || !c.received()) { blocks.add("DELIVERY_ACCEPTANCE_REQUIRED"); }
        if (!"TOTAL_COLLECTED".equals(feeBasis)) { blocks.add("FEE_BASIS_NOT_AGREED"); }
        if (!List.of("HALF_UP", "DOWN", "UP").contains(rounding)) { blocks.add("ROUNDING_NOT_AGREED"); }
        return new FinanceResponse.Eligibility(blocks.isEmpty(), List.copyOf(blocks));
    }

    private void noPayout(long orderId) {
        require(repository.settlements(orderId).stream().noneMatch(s ->
                List.of("PROCESSING", "SUCCESS").contains(s.status())), "PAYOUT_ALREADY_RESERVED");
    }

    private static String key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9:_-]{8,100}")) {
            throw new FinanceException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        }
        return key;
    }

    private static void proof(String reference, String evidence) {
        require(reference != null && !reference.isBlank() && reference.length() <= 255
                && evidence != null && evidence.length() <= 1000 && evidence.matches("https://[^\\s]+"), "TRANSFER_PROOF_REQUIRED");
    }

    public FinanceResponse.Payment initiate(long paymentId, String commandKey, Actor actor) {
        String checkedKey = key(commandKey);
        Payment reserved = transaction.execute(status -> {
            var original = repository.payment(paymentId);
            var context = repository.lock(original.orderId());
            access(context, actor, true);
            // Replays never submit a second network create, including after a timeout.
            long existing = repository.count("SELECT COUNT(*) FROM payment WHERE idempotency_key=?", checkedKey);
            if (existing > 0) {
                var found = repository.payments(context.id()).stream().filter(p -> checkedKey.equals(p.key())).findFirst();
                require(found.isPresent() && found.get().type().equals(original.type()), "IDEMPOTENCY_KEY_CONFLICT");
                return found.orElseThrow();
            }
            require(context.accepted(), "ORDER_NOT_ACCEPTED");
            require(financialBlocks(context.id()).isEmpty(), "PAYMENT_BLOCKED_BY_CASE");
            noPayout(context.id());
            require(!repository.active(context.id()), "ACTIVE_PAYMENT_EXISTS");
            require(!repository.activeCustomerPayment(context.id()), "ACTIVE_PAYMENT_EXISTS");
            require(!"SUCCESS".equals(original.status()), "OBLIGATION_ALREADY_PAID");
            long paid = repository.paid(context.id());
            long amount = context.total() - paid;
            if ("DEPOSIT".equals(original.type())) {
                require(paid == 0 && context.deposit() != null, "DEPOSIT_ALREADY_SATISFIED");
                amount = context.deposit();
            } else if ("REMAINING".equals(original.type())) {
                require(context.deposit() != null && paid >= context.deposit(), "DEPOSIT_REQUIRED");
                require(context.deliveryDate() != null, "DELIVERY_DATE_REQUIRED");
            } else {
                require(paid == 0, "FULL_PAYMENT_ALREADY_STARTED");
            }
            require(amount > 0, "NO_OUTSTANDING_BALANCE");
            Instant due = "REMAINING".equals(original.type())
                    ? FinanceIntegrationService.remainingDue(context.deliveryDate()) : null;
            require(due == null || due.isAfter(Instant.now()), "PAYMENT_DEADLINE_PASSED");
            Instant expiry = Instant.now().plusSeconds(900);
            if (due != null && due.isBefore(expiry)) { expiry = due; }
            long id = original.id();
            if (original.orderCode() != null) {
                require(List.of("CANCELLED", "EXPIRED", "FAILED").contains(original.status()), "PAYMENT_RECONCILIATION_REQUIRED");
                id = repository.insert("""
                        INSERT INTO payment(seller_order_id,payment_type,amount,provider,status)
                        VALUES(?,?,?,'PAYOS','PENDING')
                        """, context.id(), original.type(), amount);
            }
            repository.update("""
                    UPDATE payment SET amount=?,due_at=?,expires_at=?,order_code=?,idempotency_key=?,
                    status='CREATING',context_version=? WHERE payment_id=?
                    """, amount, FinanceRepository.utc(due), FinanceRepository.utc(expiry), repository.nextOrderCode(),
                    checkedKey, context.sourceVersion(), id);
            repository.event(context.id(), "PAYMENT_LINK_RESERVED", id, Long.toString(actor.userId()), original.type());
            return repository.payment(id);
        });
        // A committed reservation can only be sent by this caller. A replay sees CREATING and reconciles.
        if (!"CREATING".equals(reserved.status())) { return reserved.response(); }
        // Claim once in a separate short transaction before making a network request.
        boolean claimed = Boolean.TRUE.equals(transaction.execute(status -> {
            repository.lock(reserved.orderId());
            return repository.update("UPDATE payment SET status='RECONCILING' WHERE payment_id=? AND status='CREATING'", reserved.id()) == 1;
        }));
        if (!claimed) { return repository.payment(reserved.id()).response(); }
        try {
            var link = provider.create(reserved.orderCode(), reserved.amount(), reserved.expiresAt(),
                    PaymentDescription.seller(repository.sellerOrderNumber(reserved.orderId()),
                            reserved.orderId(), reserved.type()));
            return transaction.execute(status -> {
                repository.lock(reserved.orderId());
                require(link.orderCode() == reserved.orderCode() && link.amount() == reserved.amount()
                        && link.id() != null, "PROVIDER_LINK_MISMATCH");
                repository.update("""
                        UPDATE payment SET payment_link_id=?,checkout_url=?,qr_code=?,
                        status=CASE WHEN status='SUCCESS' THEN status ELSE 'PENDING' END WHERE payment_id=?
                        """, link.id(), link.checkoutUrl(), link.qrCode(), reserved.id());
                repository.event(reserved.orderId(), "PAYMENT_LINK_CREATED", reserved.id(), "PAYOS", "Link ready");
                return repository.payment(reserved.id()).response();
            });
        } catch (RuntimeException ex) {
            // Reservation remains durable; query the same orderCode before any retry.
            return repository.payment(reserved.id()).response();
        }
    }

    public void webhook(Webhook webhook) {
        var receipt = provider.verify(webhook);
        // Official signed registration sample; reserved outside our order-code sequence.
        // Acknowledge only this exact probe after SDK verification, without inventing a payment.
        if (receipt.orderCode() == 123 && receipt.amount() == 3000 && "VND".equals(receipt.currency())
                && "124c33293c43417ab7879e14c8d9eb18".equals(receipt.linkId())
                && "TF230204212323".equals(receipt.reference())) {
            transaction.executeWithoutResult(status -> {
                if (repository.update("UPDATE finance_provider_probe SET received_at=? WHERE provider_reference=?",
                        FinanceRepository.utc(Instant.now()), receipt.reference()) == 0) {
                    repository.update("INSERT INTO finance_provider_probe(provider_reference,received_at) VALUES(?,?)",
                            receipt.reference(), FinanceRepository.utc(Instant.now()));
                }
            });
            return;
        }
        if (customerPayments != null && customerPayments.receive(receipt)) { return; }
        var payment = repository.byOrderCode(receipt.orderCode());
        transaction.executeWithoutResult(status -> {
            repository.lock(payment.orderId());
            var current = repository.payment(payment.id());
            require(receipt.amount() == current.amount() && "VND".equals(receipt.currency())
                    && receipt.linkId() != null && (current.linkId() == null || receipt.linkId().equals(current.linkId())),
                    "WEBHOOK_PAYMENT_MISMATCH");
            require(receipt.reference() != null && receipt.reference().length() <= 255, "INVALID_PROVIDER_REFERENCE");
            collect(current, receipt.linkId(), receipt.reference(), receipt.amount());
        });
    }

    private void collect(Payment p, String linkId, String reference, long amount) {
        long duplicate = repository.count("SELECT COUNT(*) FROM finance_webhook_inbox WHERE provider_reference=?", reference);
        if (duplicate > 0) {
            require(repository.count("SELECT COUNT(*) FROM finance_webhook_inbox WHERE provider_reference=? AND payment_id=? AND amount=?",
                    reference, p.id(), amount) == 1, "PROVIDER_REFERENCE_CONFLICT");
            return;
        }
        require(repository.count("SELECT COUNT(*) FROM customer_payment_webhook_inbox WHERE provider_reference=?",
                reference) == 0, "PROVIDER_REFERENCE_CONFLICT");
        // SDK-verified late payments are still real money, even if expiry/cancellation raced with collection.
        repository.update("INSERT INTO finance_webhook_inbox(provider_reference,payment_id,amount,received_at) VALUES(?,?,?,?)",
                reference, p.id(), amount, FinanceRepository.utc(Instant.now()));
        if ("SUCCESS".equals(p.status())) { return; }
        require(repository.paid(p.orderId()) + amount <= repository.context(p.orderId()).total(), "OVERPAYMENT_REVIEW_REQUIRED");
        repository.update("""
                UPDATE payment SET status='SUCCESS',hold_status='HELD',transaction_ref=?,payment_link_id=?,paid_at=?
                WHERE payment_id=?
                """, reference, linkId, FinanceRepository.utc(Instant.now()), p.id());
        repository.ledger(p.orderId(), "payment:" + p.id(), "HELD", amount);
        repository.event(p.orderId(), "PAYMENT_SUCCESS", p.id(), "PAYOS", "HELD internal ledger");
    }

    public FinanceResponse.Payment reconcile(long paymentId, Actor actor, boolean cancel) {
        var reservation = transaction.execute(status -> {
            var p = repository.payment(paymentId);
            var c = repository.lock(p.orderId());
            access(c, actor, cancel);
            require(p.orderCode() != null, "PAYMENT_LINK_NOT_CREATED");
            return p;
        });
        if ("SUCCESS".equals(reservation.status())) {
            return paymentResponse(reservation, repository.context(reservation.orderId()), actor);
        }
        PaymentProvider.Status remote;
        try {
            remote = cancel ? provider.cancel(reservation.orderCode()) : provider.get(reservation.orderCode());
        } catch (RuntimeException ex) {
            throw new FinanceException(HttpStatus.SERVICE_UNAVAILABLE, "PAYOS_RECONCILIATION_UNAVAILABLE");
        }
        return transaction.execute(status -> {
            repository.lock(reservation.orderId());
            var current = repository.payment(paymentId);
            require(remote.orderCode() == current.orderCode() && remote.amount() == current.amount()
                    && (current.linkId() == null || current.linkId().equals(remote.linkId())), "PROVIDER_LINK_MISMATCH");
            if ("PAID".equals(remote.status()) && remote.paid() == current.amount() && remote.reference() != null) {
                collect(current, remote.linkId(), remote.reference(), remote.paid());
            } else if (!"SUCCESS".equals(current.status())) {
                String next = remote.paid() > 0 ? "RECONCILING"
                        : List.of("CANCELLED", "EXPIRED").contains(remote.status()) ? remote.status() : "PENDING";
                repository.update("UPDATE payment SET status=?,payment_link_id=? WHERE payment_id=?", next, remote.linkId(), paymentId);
                repository.event(current.orderId(), "PAYMENT_RECONCILED", paymentId, Long.toString(actor.userId()), next);
            }
            return paymentResponse(repository.payment(paymentId), repository.context(reservation.orderId()), actor);
        });
    }

    /** All commands serialize against the same finance_context row, including dispute/payout races. */
    private void command(long orderId, String commandKey, String fingerprint, Actor actor, Runnable action) {
        String checked = key(commandKey);
        transaction.executeWithoutResult(status -> {
            access(repository.lock(orderId), actor, false);
            String hash;
            try {
                hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest((orderId + ":" + actor.userId() + ":" + fingerprint).getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
            if (repository.count("SELECT COUNT(*) FROM finance_command WHERE command_key=?", checked) > 0) {
                require(repository.count("SELECT COUNT(*) FROM finance_command WHERE command_key=? AND fingerprint=? AND seller_order_id=?",
                        checked, hash, orderId) == 1, "IDEMPOTENCY_KEY_CONFLICT");
                return;
            }
            repository.update("INSERT INTO finance_command(command_key,fingerprint,seller_order_id) VALUES(?,?,?)", checked, hash, orderId);
            action.run();
        });
    }

    public void requestCancellation(long orderId, String reason, String commandKey, Actor actor) {
        command(orderId, commandKey, "cancel:" + reason, actor, () -> {
            noPayout(orderId);
            require(repository.cancellations(orderId).isEmpty(), "CANCELLATION_ALREADY_EXISTS");
            long id = repository.insert("""
                    INSERT INTO cancellation(seller_order_id,actor_type,actor_id_ref,reason,status,requested_at)
                    VALUES(?,?,?,?,'REQUESTED',?)
                    """, orderId, actor.admin() ? "ADMIN" : isBuyer(repository.context(orderId), actor)
                            ? "CUSTOMER" : "SUPPLIER", actor.userId(), reason, FinanceRepository.utc(Instant.now()));
            repository.event(orderId, "CANCELLATION_REQUESTED", id, Long.toString(actor.userId()), reason);
        });
    }

    public void decideCancellation(long orderId, long id, boolean approve, String reason, String commandKey, Actor actor) {
        admin(actor);
        command(orderId, commandKey, "cancel-decision:" + id + ":" + approve + ":" + reason, actor, () -> {
            noPayout(orderId);
            require(repository.cancellations(orderId).stream().anyMatch(c -> c.id() == id && "REQUESTED".equals(c.status())), "CANCELLATION_NOT_PENDING");
            require(!approve || !repository.active(orderId), "CANCEL_UNPAID_LINKS_FIRST");
            repository.update("UPDATE cancellation SET status=?,decision_reason=?,cancelled_at=? WHERE cancellation_id=?",
                    approve ? "APPROVED" : "REJECTED", reason, approve ? FinanceRepository.utc(Instant.now()) : null, id);
            repository.event(orderId, "CANCELLATION_DECIDED", id, Long.toString(actor.userId()), reason);
        });
    }

    public void openDispute(long orderId, String reason, String evidence, String commandKey, Actor actor) {
        command(orderId, commandKey, "dispute:" + reason + ":" + evidence, actor, () -> {
            require(repository.settlements(orderId).stream().noneMatch(s -> "PROCESSING".equals(s.status())), "PAYOUT_ALREADY_RESERVED");
            long id = repository.insert("""
                    INSERT INTO dispute(seller_order_id,opened_by_user_id_ref,reason,evidence_url,status,opened_at)
                    VALUES(?,?,?,?,'OPEN',?)
                    """, orderId, actor.userId(), reason, evidence, FinanceRepository.utc(Instant.now()));
            repository.event(orderId, "DISPUTE_OPENED", id, Long.toString(actor.userId()), reason);
        });
    }

    public void resolveDispute(long orderId, long id, String resolution, String commandKey, Actor actor) {
        admin(actor);
        require(adjudicationEnabled, "DISPUTE_AUTHORITY_NOT_CONFIGURED");
        command(orderId, commandKey, "resolve:" + id + ":" + resolution, actor, () -> {
            require(repository.disputes(orderId).stream().anyMatch(c -> c.id() == id && "OPEN".equals(c.status())), "DISPUTE_NOT_OPEN");
            repository.update("UPDATE dispute SET status='RESOLVED',resolution=?,resolved_at=? WHERE dispute_id=?",
                    resolution, FinanceRepository.utc(Instant.now()), id);
            repository.event(orderId, "DISPUTE_RESOLVED", id, Long.toString(actor.userId()), resolution);
        });
    }

    public void addEvidence(long orderId, long id, String evidence, String commandKey, Actor actor) {
        command(orderId, commandKey, "evidence:" + id + ":" + evidence, actor, () -> {
            require(repository.disputes(orderId).stream().anyMatch(c -> c.id() == id && "OPEN".equals(c.status())), "DISPUTE_NOT_OPEN");
            // Append to immutable audit history; preserve the original evidence link on the dispute.
            repository.event(orderId, "DISPUTE_EVIDENCE_ADDED", id, Long.toString(actor.userId()), evidence);
        });
    }

    public void rejectRefund(long orderId, long id, String reason, String commandKey, Actor actor) {
        admin(actor);
        command(orderId, commandKey, "reject-refund:" + id + ":" + reason, actor, () -> {
            require(repository.refunds(orderId).stream().anyMatch(c -> c.id() == id && "MANUAL_REVIEW".equals(c.status())), "REFUND_NOT_PENDING");
            repository.update("UPDATE refund SET status='REJECTED' WHERE refund_id=?", id);
            repository.event(orderId, "REFUND_REJECTED", id, Long.toString(actor.userId()), reason);
        });
    }

    public void requestRefund(long orderId, long paymentId, long amount, String reason, String policy,
            String commandKey, Actor actor) {
        admin(actor);
        require(policy != null && !policy.isBlank() && policy.length() <= 2000, "REFUND_POLICY_DECISION_REQUIRED");
        command(orderId, commandKey, "refund:" + paymentId + ":" + amount + ":" + reason + ":" + policy, actor, () -> {
            noPayout(orderId);
            var payment = repository.payment(paymentId);
            require(payment.orderId() == orderId && "SUCCESS".equals(payment.status()) && "HELD".equals(payment.hold()), "PAYMENT_NOT_REFUNDABLE");
            long reserved = repository.count("SELECT COALESCE(SUM(amount),0) FROM refund WHERE payment_id=? AND status<>'REJECTED'", paymentId);
            require(amount > 0 && amount <= payment.amount() - reserved, "REFUND_EXCEEDS_AVAILABLE_AMOUNT");
            // No automatic percentage: an authorized manual policy decision is mandatory and snapshotted.
            long id = repository.insert("""
                    INSERT INTO refund(payment_id,amount,reason,status,requested_at,idempotency_key,policy_snapshot)
                    VALUES(?,?,?,'MANUAL_REVIEW',?,?,?)
                    """, paymentId, amount, reason, FinanceRepository.utc(Instant.now()), commandKey, policy);
            repository.update("UPDATE refund SET transfer_reference=? WHERE refund_id=?", "mss-refund-" + id, id);
            repository.event(orderId, "REFUND_REVIEW_REQUESTED", id, Long.toString(actor.userId()), policy);
        });
    }

    public void confirmRefund(long orderId, long id, String reference, String evidence, String commandKey, Actor actor) {
        admin(actor);
        proof(reference, evidence);
        command(orderId, commandKey, "refund-confirm:" + id + ":" + reference + ":" + evidence, actor, () -> {
            noPayout(orderId);
            var refund = repository.refunds(orderId).stream().filter(r -> r.id() == id).findFirst().orElseThrow(
                    () -> new FinanceException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND"));
            require("MANUAL_REVIEW".equals(refund.status()), "REFUND_NOT_PENDING");
            repository.update("UPDATE refund SET status='SUCCESS',transfer_reference=?,evidence_url=?,refunded_at=? WHERE refund_id=?",
                    reference, evidence, FinanceRepository.utc(Instant.now()), id);
            repository.ledger(orderId, "refund:" + id, "REFUNDED", refund.amount());
            repository.event(orderId, "REFUND_SUCCESS", id, Long.toString(actor.userId()), reference);
        });
    }

    public void createSettlement(long orderId, String commandKey, Actor actor) {
        admin(actor);
        command(orderId, commandKey, "settlement", actor, () -> {
            var context = repository.context(orderId);
            require(eligibility(context).eligible(), "SETTLEMENT_NOT_ELIGIBLE");
            require(repository.settlements(orderId).isEmpty(), "SETTLEMENT_ALREADY_EXISTS");
            long fee = BigDecimal.valueOf(context.total()).multiply(new BigDecimal("0.05"))
                    .setScale(0, RoundingMode.valueOf(rounding)).longValueExact();
            long id = repository.insert("""
                    INSERT INTO settlement(seller_order_id,gross_amount,fee_rate,fee_amount,supplier_payout_amount,
                    status,idempotency_key,fee_basis,rounding_rule) VALUES(?,?,5.00,?,?,'READY',?,?,?)
                    """, orderId, context.total(), fee, context.total() - fee, commandKey, feeBasis, rounding);
            repository.event(orderId, "SETTLEMENT_READY", id, Long.toString(actor.userId()), "feeRate=5;basis=" + feeBasis + ";rounding=" + rounding);
        });
    }

    public void beginPayout(long orderId, long id, String commandKey, Actor actor) {
        admin(actor);
        command(orderId, commandKey, "payout:" + id, actor, () -> {
            require(eligibility(repository.context(orderId)).eligible(), "SETTLEMENT_NOT_ELIGIBLE");
            require(repository.settlements(orderId).stream().anyMatch(s -> s.settlementId() == id && "READY".equals(s.status())), "SETTLEMENT_NOT_READY");
            repository.update("UPDATE settlement SET status='PROCESSING',payout_reference=? WHERE settlement_id=?",
                    "mss-settlement-" + id, id);
            repository.event(orderId, "PAYOUT_MANUAL_PROCESSING", id, Long.toString(actor.userId()), "reference=mss-settlement-" + id);
        });
    }

    public void confirmPayout(long orderId, long id, String reference, String evidence, String commandKey, Actor actor) {
        admin(actor);
        proof(reference, evidence);
        command(orderId, commandKey, "payout-confirm:" + id + ":" + reference + ":" + evidence, actor, () -> {
            require(eligibility(repository.context(orderId)).eligible(), "SETTLEMENT_NOT_ELIGIBLE");
            var settlement = repository.settlements(orderId).stream().filter(s -> s.settlementId() == id).findFirst().orElseThrow(
                    () -> new FinanceException(HttpStatus.NOT_FOUND, "SETTLEMENT_NOT_FOUND"));
            require("PROCESSING".equals(settlement.status()), "PAYOUT_NOT_PROCESSING");
            repository.update("UPDATE settlement SET status='SUCCESS',payout_reference=?,evidence_url=?,settled_at=? WHERE settlement_id=?",
                    reference, evidence, FinanceRepository.utc(Instant.now()), id);
            repository.update("UPDATE payment SET hold_status='RELEASED' WHERE seller_order_id=? AND status='SUCCESS' AND hold_status='HELD'", orderId);
            repository.ledger(orderId, "payout:" + id, "RELEASED", settlement.supplierPayoutAmount());
            if (settlement.feeAmount() > 0) { repository.ledger(orderId, "fee:" + id, "PLATFORM_FEE", settlement.feeAmount()); }
            repository.event(orderId, "SETTLEMENT_SUCCESS", id, Long.toString(actor.userId()), reference);
        });
    }
}
