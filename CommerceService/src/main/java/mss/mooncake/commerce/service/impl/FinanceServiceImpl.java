package mss.mooncake.commerce.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import feign.FeignException;
import mss.mooncake.commerce.client.IdentityServiceClient;
import mss.mooncake.commerce.client.dto.identity.PayoutAccountResponse;
import mss.mooncake.commerce.client.PaymentProvider;
import mss.mooncake.commerce.dto.response.FinanceResponse;
import mss.mooncake.commerce.dto.response.TransferInstructionResponse;
import mss.mooncake.commerce.exception.FinanceException;
import mss.mooncake.commerce.notification.CommerceNotifier;
import mss.mooncake.commerce.repository.FinanceRepository;
import mss.mooncake.commerce.repository.FinanceRepository.Context;
import mss.mooncake.commerce.repository.FinanceRepository.Payment;
import mss.mooncake.commerce.service.FinanceService;
import mss.mooncake.commerce.service.FinanceIntegrationService;
import mss.mooncake.commerce.util.PaymentDescription;
import mss.mooncake.commerce.service.CustomerPaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.payos.model.webhooks.Webhook;

@Service
public class FinanceServiceImpl implements FinanceService {
    private static final Logger log = LoggerFactory.getLogger(FinanceServiceImpl.class);
    private final FinanceRepository repository;
    private final PaymentProvider provider;
    private final TransactionTemplate transaction;
    private final String feeBasis;
    private final String rounding;
    private final boolean adjudicationEnabled;
    private final CustomerPaymentService customerPayments;
    private final FinanceIntegrationService integration;
    private final long shippingSlaDays;
    private final CommerceNotifier notifier;
    private final IdentityServiceClient identity;

    @Autowired
    public FinanceServiceImpl(FinanceRepository repository, PaymentProvider provider,
            PlatformTransactionManager manager,
            @Value("${finance.settlement.fee-basis:}") String feeBasis,
            @Value("${finance.settlement.rounding:}") String rounding,
            @Value("${finance.dispute.admin-adjudication-enabled:false}") boolean adjudicationEnabled,
            CustomerPaymentService customerPayments, FinanceIntegrationService integration,
            @Value("${commerce.retail.shipping-sla-days:7}") long shippingSlaDays, CommerceNotifier notifier,
            IdentityServiceClient identity) {
        this.repository = repository;
        this.provider = provider;
        this.transaction = new TransactionTemplate(manager);
        this.feeBasis = feeBasis;
        this.rounding = rounding;
        this.adjudicationEnabled = adjudicationEnabled;
        this.customerPayments = customerPayments;
        this.integration = integration;
        this.shippingSlaDays = shippingSlaDays;
        this.notifier = notifier;
        this.identity = identity;
    }

    public FinanceServiceImpl(FinanceRepository repository, PaymentProvider provider,
            PlatformTransactionManager manager, String feeBasis, String rounding, boolean adjudicationEnabled,
            CustomerPaymentService customerPayments, FinanceIntegrationService integration,
            long shippingSlaDays, CommerceNotifier notifier) {
        this(repository, provider, manager, feeBasis, rounding, adjudicationEnabled, customerPayments,
                integration, shippingSlaDays, notifier, null);
    }

    /** Without in-app notifications, for tests that do not check them. */
    public FinanceServiceImpl(FinanceRepository repository, PaymentProvider provider,
            PlatformTransactionManager manager, String feeBasis, String rounding, boolean adjudicationEnabled,
            CustomerPaymentService customerPayments, FinanceIntegrationService integration, long shippingSlaDays) {
        this(repository, provider, manager, feeBasis, rounding, adjudicationEnabled, customerPayments,
                integration, shippingSlaDays, CommerceNotifier.disabled());
    }

    public FinanceServiceImpl(FinanceRepository repository, PaymentProvider provider,
            PlatformTransactionManager manager, String feeBasis, String rounding, boolean adjudicationEnabled,
            CustomerPaymentService customerPayments, FinanceIntegrationService integration) {
        this(repository, provider, manager, feeBasis, rounding, adjudicationEnabled, customerPayments,
                integration, 7);
    }

    // Direct construction remains useful for the isolated finance unit tests.
    public FinanceServiceImpl(FinanceRepository repository, PaymentProvider provider,
            PlatformTransactionManager manager, String feeBasis, String rounding, boolean adjudicationEnabled) {
        this(repository, provider, manager, feeBasis, rounding, adjudicationEnabled, null,
                new FinanceIntegrationServiceImpl(repository));
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

    private FinanceResponse.Payment paymentResponse(Payment payment, Context context, Actor actor, long reserved) {
        if (actor.admin()) {
            long available = "SUCCESS".equals(payment.status()) && "HELD".equals(payment.hold())
                    ? Math.max(0, payment.amount() - reserved) : 0;
            return new FinanceResponse.Payment(payment.id(), payment.orderId(), payment.type(), payment.amount(),
                    payment.status(), payment.hold(), payment.dueAt(), payment.expiresAt(),
                    isBuyer(context, actor) ? payment.checkoutUrl() : null,
                    isBuyer(context, actor) ? payment.response().qrCode() : null, available);
        }
        return paymentResponse(payment, context, actor);
    }

    private FinanceResponse.Payment paymentResponse(Payment payment, Context context, Actor actor) {
        if (isBuyer(context, actor)) { return payment.response(); }
        return new FinanceResponse.Payment(payment.id(), payment.orderId(), payment.type(), payment.amount(),
                payment.status(), payment.hold(), payment.dueAt(), payment.expiresAt(), null, null);
    }

    @Override
    public FinanceResponse summaryByNumber(String sellerOrderNo, Actor actor) {
        admin(actor);
        Long id = repository.sellerOrderIdByNumber(sellerOrderNo.strip());
        if (id == null) throw new FinanceException(HttpStatus.NOT_FOUND, "SELLER_ORDER_NOT_FOUND");
        return summary(id, actor);
    }

    public FinanceResponse summary(long orderId, Actor actor) {
        return transaction.execute(status -> {
            var context = repository.lock(orderId);
            access(context, actor, false);
            Instant deliveredAt = context.delivered() ? repository.deliveredAt(orderId) : null;
            var reserved = actor.admin() ? repository.reservedRefundAmounts(orderId) : java.util.Map.<Long, Long>of();
            return new FinanceResponse(new FinanceResponse.Context(orderId, context.total(), context.deposit(),
                    context.deliveryDate(), context.accepted(), repository.paid(orderId),
                    FinanceIntegrationService.remainingDue(context.deliveryDate()),
                    deliveredAt == null ? null : deliveredAt.plus(Duration.ofDays(7))),
                    repository.payments(orderId).stream().map(p -> paymentResponse(p, context, actor, reserved.getOrDefault(p.id(), 0L))).toList(),
                    repository.cancellations(orderId), repository.refunds(orderId), repository.disputes(orderId),
                    repository.settlements(orderId), eligibility(context), repository.history(orderId));
        });
    }

    @Override
    public TransferInstructionResponse transferInstructions(long orderId, Actor actor) {
        admin(actor);
        repository.context(orderId);
        return new TransferInstructionResponse(repository.transferDestinations(orderId).stream().map(row -> {
            String memo = "REFUND".equals(row.kind()) ? "MSSREFUND" + row.id() : "MSSPAYOUT" + row.id();
            String qr = row.bankBin() == null ? null : "https://img.vietqr.io/image/"
                    + row.bankBin() + "-" + row.accountNumber() + "-compact2.png?amount=" + row.amount()
                    + "&addInfo=" + memo;
            return new TransferInstructionResponse.Transfer(row.kind(), row.id(), row.amount(), row.status(),
                    memo, row.bankBin(), row.bankName(), row.accountNumber(), row.accountHolder(), qr);
        }).toList());
    }

    private PayoutAccountResponse destination(boolean customer, long id) {
        if (identity == null) throw new FinanceException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE");
        try {
            var account = customer ? identity.getCustomerPayoutAccount(id) : identity.getSupplierPayoutAccount(id);
            require(account != null && (customer ? "CUSTOMER" : "SUPPLIER").equals(account.ownerKind())
                    && account.bankBin() != null && account.bankBin().matches("[0-9]{6}")
                    && account.accountNumber() != null && account.accountNumber().matches("[0-9]{6,19}")
                    && account.bankName() != null && !account.bankName().isBlank()
                    && account.accountHolder() != null && !account.accountHolder().isBlank(), "RECIPIENT_ACCOUNT_INVALID");
            return account;
        } catch (FeignException.NotFound missing) {
            throw new FinanceException(HttpStatus.CONFLICT, "RECIPIENT_ACCOUNT_MISSING");
        } catch (FeignException unavailable) {
            throw new FinanceException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE");
        }
    }

    @Override
    public void prepareRefundTransfer(long orderId, long refundId, String commandKey, Actor actor) {
        admin(actor);
        var account = destination(true, repository.context(orderId).customerId());
        command(orderId, commandKey, "refund-transfer:" + refundId, actor, () -> {
            var refund = repository.transferDestinations(orderId).stream()
                    .filter(row -> "REFUND".equals(row.kind()) && row.id() == refundId).findFirst()
                    .orElseThrow(() -> new FinanceException(HttpStatus.CONFLICT, "REFUND_NOT_PENDING"));
            if (refund.bankBin() != null) return; // A retry never silently redirects an approved transfer.
            repository.update("""
                    UPDATE refund SET bank_bin=?,bank_name=?,bank_account_number=?,bank_account_holder=?,bank_snapshot_at=?
                    WHERE refund_id=? AND bank_bin IS NULL
                    """, account.bankBin(), account.bankName(), account.accountNumber(), account.accountHolder(),
                    FinanceRepository.utc(Instant.now()), refundId);
            repository.event(orderId, "REFUND_DESTINATION_SNAPSHOTTED", refundId, Long.toString(actor.userId()),
                    "bankBin=" + account.bankBin());
        });
    }

    @Override
    public void preparePayoutTransfer(long orderId, long settlementId, String commandKey, Actor actor) {
        admin(actor);
        var account = destination(false, repository.context(orderId).supplierId());
        command(orderId, commandKey, "payout-transfer:" + settlementId, actor, () -> {
            var payout = repository.transferDestinations(orderId).stream()
                    .filter(row -> "PAYOUT".equals(row.kind()) && row.id() == settlementId).findFirst()
                    .orElseThrow(() -> new FinanceException(HttpStatus.CONFLICT, "PAYOUT_NOT_PROCESSING"));
            if (payout.bankBin() != null) return;
            repository.update("""
                    UPDATE settlement SET bank_bin=?,bank_name=?,bank_account_number=?,bank_account_holder=?,bank_snapshot_at=?
                    WHERE settlement_id=? AND bank_bin IS NULL
                    """, account.bankBin(), account.bankName(), account.accountNumber(), account.accountHolder(),
                    FinanceRepository.utc(Instant.now()), settlementId);
            repository.event(orderId, "PAYOUT_DESTINATION_SNAPSHOTTED", settlementId, Long.toString(actor.userId()),
                    "bankBin=" + account.bankBin());
        });
    }

    private List<String> financialBlocks(long id) {
        List<String> blocks = new ArrayList<>();
        if (repository.disputes(id).stream().anyMatch(c -> !"RESOLVED".equals(c.status()))) { blocks.add("OPEN_DISPUTE"); }
        if (repository.cancellations(id).stream().anyMatch(c -> !"REJECTED".equals(c.status()))) { blocks.add("CANCELLATION"); }
        if (repository.refunds(id).stream().anyMatch(c -> "MANUAL_REVIEW".equals(c.status()))) { blocks.add("REFUND"); }
        return blocks;
    }

    /** The order was cancelled with its deposit kept for the supplier: only that part is settled, without delivery. */
    private boolean keptDeposit(long id) {
        return repository.count("""
                SELECT COUNT(*) FROM seller_order s JOIN cancellation c ON c.seller_order_id=s.seller_order_id
                WHERE s.seller_order_id=? AND s.status='CANCELLED' AND c.status='APPROVED' AND c.retained_amount>0
                """, id) > 0;
    }

    /** Settlement base before refunds: what was collected for a kept deposit, the order total otherwise. */
    private long settlementGross(Context c) {
        return keptDeposit(c.id()) ? repository.paid(c.id()) : c.total();
    }

    private FinanceResponse.Eligibility eligibility(Context c) {
        var blocks = financialBlocks(c.id());
        if (keptDeposit(c.id())) {
            // The approved cancellation is the reason for this payout, not a block.
            blocks.remove("CANCELLATION");
            if (repository.refunded(c.id()) >= repository.paid(c.id())) { blocks.add("FULLY_REFUNDED"); }
            if (repository.active(c.id())) { blocks.add("PAYMENT_RECONCILIATION_REQUIRED"); }
        } else {
            if (!c.accepted()) { blocks.add("ORDER_NOT_ACCEPTED"); }
            if (repository.paid(c.id()) != c.total()) { blocks.add("NOT_FULLY_PAID"); }
            if (repository.refunded(c.id()) >= c.total()) { blocks.add("FULLY_REFUNDED"); }
            if (repository.active(c.id())) { blocks.add("PAYMENT_RECONCILIATION_REQUIRED"); }
            Instant deliveredAt = repository.deliveredAt(c.id());
            boolean adjudicatedAfterDelivery = deliveredAt != null && repository.disputes(c.id()).stream().anyMatch(d ->
                    "RESOLVED".equals(d.status()) && d.decisionType() != null
                            && d.openedAt() != null && !d.openedAt().isBefore(deliveredAt));
            if (!c.delivered() || (!c.received() && !adjudicatedAfterDelivery)) {
                blocks.add("DELIVERY_ACCEPTANCE_REQUIRED");
            }
            if (deliveredAt == null || Instant.now().isBefore(deliveredAt.plus(Duration.ofDays(7)))) {
                blocks.add("DISPUTE_WINDOW_OPEN");
            }
        }
        if (!"NET_AFTER_REFUND".equals(feeBasis)
                && !("TOTAL_COLLECTED".equals(feeBasis) && repository.refunded(c.id()) == 0)) {
            blocks.add("FEE_BASIS_NOT_AGREED");
        }
        if (!List.of("HALF_UP", "DOWN", "UP").contains(rounding)) { blocks.add("ROUNDING_NOT_AGREED"); }
        return new FinanceResponse.Eligibility(blocks.isEmpty(), List.copyOf(blocks));
    }

    private void noPayout(long orderId) {
        require(repository.settlements(orderId).stream().noneMatch(s ->
                List.of("PROCESSING", "SUCCESS").contains(s.status())), "PAYOUT_ALREADY_RESERVED");
    }

    private void holdReadySettlement(long orderId, Actor actor) {
        for (var settlement : repository.settlements(orderId)) {
            if ("READY".equals(settlement.status())) {
                repository.update("UPDATE settlement SET status='ON_HOLD' WHERE settlement_id=?", settlement.settlementId());
                repository.event(orderId, "SETTLEMENT_ON_HOLD", settlement.settlementId(),
                        Long.toString(actor.userId()), "Case or refund requires recalculation");
            }
        }
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
            // Retail is paid once for the whole checkout (customer-order payment), never per seller order.
            require(!repository.retailOrder(repository.parentOrderId(context.id())),
                    "RETAIL_CUSTOMER_ORDER_PAYMENT_REQUIRED");
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
                    PaymentDescription.seller(repository.context(reserved.orderId()).supplierId(), reserved.type()));
            return transaction.execute(status -> {
                repository.lock(reserved.orderId());
                require(link.orderCode() == reserved.orderCode() && link.amount() == reserved.amount()
                        && link.id() != null, "PROVIDER_LINK_MISMATCH");
                // A reconciliation that ran meanwhile may have settled or cancelled the payment: never revive it.
                repository.update("""
                        UPDATE payment SET payment_link_id=?,checkout_url=?,qr_code=?,
                        status=CASE WHEN status IN ('RECONCILING','PENDING') THEN 'PENDING' ELSE status END WHERE payment_id=?
                        """, link.id(), link.checkoutUrl(), link.qrCode(), reserved.id());
                repository.event(reserved.orderId(), "PAYMENT_LINK_CREATED", reserved.id(), "PAYOS", "Link ready");
                return repository.payment(reserved.id()).response();
            });
        } catch (RuntimeException ex) {
            log.warn("PayOS link creation failed for payment {} (orderCode {}): {}", reserved.id(),
                    reserved.orderCode(), PaymentProvider.describe(ex), ex);
            // PayOS may have made the link without its URL reaching us; reconcile the same orderCode now.
            try {
                return reconcile(reserved.id(), actor, false);
            } catch (RuntimeException unresolved) {
                log.warn("Payment {} stays RECONCILING after the failed create: {}", reserved.id(), unresolved.getMessage());
                return repository.payment(reserved.id()).response();
            }
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
        integration.syncCollectedPayment(p.orderId());
        notifier.paymentCollected(p.orderId(), p.id(), p.type(), amount);
    }

    public FinanceResponse.Payment reconcile(long paymentId, Actor actor, boolean cancel) {
        var reservation = transaction.execute(status -> {
            var p = repository.payment(paymentId);
            var c = repository.lock(p.orderId());
            if (cancel) {
                require(isBuyer(c, actor) || actor.admin(), "FINANCE_ACCESS_DENIED");
            } else {
                access(c, actor, false);
            }
            require(p.orderCode() != null, "PAYMENT_LINK_NOT_CREATED");
            return p;
        });
        if ("SUCCESS".equals(reservation.status())) {
            return paymentResponse(reservation, repository.context(reservation.orderId()), actor);
        }
        PaymentProvider.Status fetched;
        try {
            fetched = cancel ? provider.cancel(reservation.orderCode()) : provider.get(reservation.orderCode());
            // PayOS made this link but its URL never reached us, so the buyer cannot open it: cancel it so a
            // new link can be issued. Re-read the row first: a create still in flight may have stored the URL.
            if (!cancel && "PENDING".equals(fetched.status()) && fetched.paid() == 0
                    && repository.payment(paymentId).checkoutUrl() == null) {
                fetched = provider.cancel(reservation.orderCode());
            }
        } catch (RuntimeException ex) {
            log.warn("PayOS reconciliation failed for payment {} (orderCode {}): {}", paymentId,
                    reservation.orderCode(), PaymentProvider.describe(ex), ex);
            throw new FinanceException(HttpStatus.SERVICE_UNAVAILABLE, "PAYOS_RECONCILIATION_UNAVAILABLE");
        }
        var remote = fetched;
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
            require(repository.count("SELECT COUNT(*) FROM seller_order WHERE seller_order_id=? "
                    + "AND status NOT IN ('CANCEL_REQUESTED','CANCELLED','DELIVERED','ACCEPTED','SETTLED','COMPLETED','DISPUTED')",
                    orderId) == 1, "ORDER_CANNOT_BE_CANCELLED");
            // A rejected request is history, not a block: only one REQUESTED/APPROVED case may exist.
            require(repository.cancellations(orderId).stream().allMatch(c -> "REJECTED".equals(c.status())),
                    "CANCELLATION_ALREADY_EXISTS");
            var context = repository.context(orderId);
            String requester = actor.admin() ? "ADMIN" : isBuyer(context, actor) ? "CUSTOMER" : "SUPPLIER";
            // A buyer who cancels after the supplier started preparing leaves the deposit to the supplier.
            long retained = "CUSTOMER".equals(requester) && context.deposit() != null && repository.count(
                    "SELECT COUNT(*) FROM seller_order WHERE seller_order_id=? AND status IN ('PREPARING','SHIPPING')",
                    orderId) == 1 ? Math.min(context.deposit(), repository.paid(orderId)) : 0;
            long id = repository.insert("""
                    INSERT INTO cancellation(seller_order_id,actor_type,actor_id_ref,reason,status,requested_at,retained_amount)
                    VALUES(?,?,?,?,'REQUESTED',?,?)
                    """, orderId, requester, actor.userId(), reason, FinanceRepository.utc(Instant.now()), retained);
            repository.event(orderId, "CANCELLATION_REQUESTED", id, Long.toString(actor.userId()), reason);
            notifier.cancellationRequested(orderId, id, requester, reason);
        });
    }

    public void decideCancellation(long orderId, long id, boolean approve, String reason, String commandKey, Actor actor) {
        admin(actor);
        command(orderId, commandKey, "cancel-decision:" + id + ":" + approve + ":" + reason, actor, () -> {
            noPayout(orderId);
            require(repository.cancellations(orderId).stream().anyMatch(c -> c.id() == id && "REQUESTED".equals(c.status())), "CANCELLATION_NOT_PENDING");
            require(!approve || (!repository.active(orderId) && !repository.activeCustomerPayment(orderId)), "CANCEL_UNPAID_LINKS_FIRST");
            require(repository.count("SELECT COUNT(*) FROM customer_order WHERE order_id=? AND status='CANCEL_REQUESTED'",
                    repository.parentOrderId(orderId)) == 0, "CANCELLATION_IN_PROGRESS");
            // Money already on its way back to the buyer for this request: rejecting would leave a refunded order running.
            require(approve || repository.count("""
                    SELECT COUNT(*) FROM refund r JOIN payment p ON p.payment_id=r.payment_id
                    JOIN cancellation c ON c.seller_order_id=p.seller_order_id
                    WHERE c.cancellation_id=? AND r.status<>'REJECTED' AND r.requested_at>=c.requested_at
                    """, id) == 0, "CANCELLATION_REFUND_STARTED");
            long parent = repository.parentOrderId(orderId);
            boolean retail = repository.retailOrder(parent);
            if (approve) {
                long paid = repository.paid(orderId);
                // Unpaid retail is cancelled as a whole checkout (cancel-unpaid), which restores its stock hold.
                require(!retail || paid > 0, "UNPAID_RETAIL_CANCEL_WHOLE_ORDER");
                // Collected money beyond a deposit kept for the supplier must first be returned:
                // only refunds confirmed with transfer proof count.
                long retained = repository.count("SELECT retained_amount FROM cancellation WHERE cancellation_id=?", id);
                require(paid <= repository.refunded(orderId) + retained, "PAID_CANCELLATION_REQUIRES_REFUND_REVIEW");
            }
            repository.update("UPDATE cancellation SET status=?,decision_reason=?,cancelled_at=? WHERE cancellation_id=?",
                    approve ? "APPROVED" : "REJECTED", reason, approve ? FinanceRepository.utc(Instant.now()) : null, id);
            if (approve) {
                repository.update("UPDATE seller_order SET status='CANCELLED' WHERE seller_order_id=?", orderId);
                integration.syncCustomerOrder(orderId);
            }
            repository.event(orderId, "CANCELLATION_DECIDED", id, Long.toString(actor.userId()), reason);
            notifier.cancellationDecided(orderId, id, approve, reason);
        });
        if (approve && customerPayments != null) {
            long parent = repository.parentOrderId(orderId);
            try {
                customerPayments.restoreCancelledRetailStock(parent);
            } catch (RuntimeException failure) {
                // The cancellation is committed; the recovery job retries the idempotent Catalog restore.
                log.warn("Stock restore for cancelled order {} deferred to recovery: {}", parent, failure.getMessage());
            }
        }
    }

    public void openDispute(long orderId, String reason, String evidence, String commandKey, Actor actor) {
        command(orderId, commandKey, "dispute:" + reason + ":" + evidence, actor, () -> {
            var context = repository.context(orderId);
            access(context, actor, true);
            noPayout(orderId);
            require(repository.paid(orderId) > 0, "DISPUTE_REQUIRES_PAYMENT");
            require(repository.disputes(orderId).stream().noneMatch(c -> !"RESOLVED".equals(c.status())),
                    "DISPUTE_ALREADY_OPEN");
            require(repository.count("SELECT COUNT(*) FROM dispute WHERE seller_order_id=? "
                    + "AND LOWER(LTRIM(RTRIM(reason)))=LOWER(?)", orderId, reason.trim()) == 0,
                    "DISPUTE_DUPLICATE");
            Instant deliveredAt = repository.deliveredAt(orderId);
            if (context.delivered() && deliveredAt != null) {
                require(!Instant.now().isAfter(deliveredAt.plus(Duration.ofDays(7))), "DISPUTE_WINDOW_CLOSED");
            } else {
                Instant promised = repository.expectedDeliveryAt(orderId);
                if (promised == null && context.deliveryDate() != null) {
                    promised = context.deliveryDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
                }
                // Without an agreed delivery date (retail) the platform shipping SLA after full payment applies;
                // a later supplier estimate cannot postpone it.
                Instant fullyPaidAt = repository.paid(orderId) >= context.total() ? repository.lastPaidAt(orderId) : null;
                if (context.deliveryDate() == null && fullyPaidAt != null) {
                    Instant sla = fullyPaidAt.plus(Duration.ofDays(shippingSlaDays));
                    promised = promised == null || sla.isBefore(promised) ? sla : promised;
                }
                require(promised != null && !Instant.now().isBefore(promised), "DELIVERY_NOT_OVERDUE");
            }
            holdReadySettlement(orderId, actor);
            long id = repository.insert("""
                    INSERT INTO dispute(seller_order_id,opened_by_user_id_ref,reason,evidence_url,status,opened_at)
                    VALUES(?,?,?,?,'OPEN',?)
                    """, orderId, actor.userId(), reason, evidence, FinanceRepository.utc(Instant.now()));
            repository.event(orderId, "DISPUTE_OPENED", id, Long.toString(actor.userId()), reason);
            notifier.disputeOpened(orderId, id, reason);
        });
    }

    public void respondDispute(long orderId, long id, String response, String commandKey, Actor actor) {
        command(orderId, commandKey, "respond:" + id + ":" + response, actor, () -> {
            var context = repository.context(orderId);
            require(!actor.admin() && actor.supplierId() != null && actor.supplierId() == context.supplierId(),
                    "SUPPLIER_REQUIRED");
            require(repository.disputes(orderId).stream().anyMatch(c -> c.id() == id && "OPEN".equals(c.status())
                    && c.respondedAt() == null), "DISPUTE_NOT_AWAITING_SELLER");
            repository.update("UPDATE dispute SET seller_response=?,responded_at=? WHERE dispute_id=?",
                    response, FinanceRepository.utc(Instant.now()), id);
            repository.event(orderId, "DISPUTE_SELLER_RESPONDED", id, Long.toString(actor.userId()), response);
        });
    }

    public void escalateDispute(long orderId, long id, String commandKey, Actor actor) {
        command(orderId, commandKey, "escalate:" + id, actor, () -> {
            require(actor.admin() || isBuyer(repository.context(orderId), actor), "FINANCE_ACCESS_DENIED");
            var dispute = repository.disputes(orderId).stream().filter(c -> c.id() == id && "OPEN".equals(c.status()))
                    .findFirst().orElseThrow(() -> new FinanceException(HttpStatus.CONFLICT, "DISPUTE_NOT_OPEN"));
            require(dispute.respondedAt() != null || !Instant.now().isBefore(dispute.openedAt().plus(Duration.ofHours(48))),
                    "SELLER_RESPONSE_WINDOW_OPEN");
            repository.update("UPDATE dispute SET status='ADMIN_REVIEW',escalated_at=? WHERE dispute_id=?",
                    FinanceRepository.utc(Instant.now()), id);
            repository.event(orderId, "DISPUTE_ESCALATED", id, Long.toString(actor.userId()), "Admin review requested");
        });
    }

    public void decideDispute(long orderId, long id, String decision, Long amount, String resolution,
            String commandKey, Actor actor) {
        admin(actor);
        require(adjudicationEnabled, "DISPUTE_AUTHORITY_NOT_CONFIGURED");
        require(decision != null && List.of("REJECT", "REFUND").contains(decision), "INVALID_DISPUTE_DECISION");
        command(orderId, commandKey, "decision:" + id + ":" + decision + ":" + amount + ":" + resolution, actor, () -> {
            require(repository.disputes(orderId).stream().anyMatch(c -> c.id() == id && "ADMIN_REVIEW".equals(c.status())),
                    "DISPUTE_NOT_IN_ADMIN_REVIEW");
            noPayout(orderId);
            if ("REJECT".equals(decision)) {
                require(amount == null, "INVALID_DISPUTE_AMOUNT");
                repository.update("UPDATE dispute SET status='RESOLVED',decision_type='REJECT',resolution=?,resolved_at=? WHERE dispute_id=?",
                        resolution, FinanceRepository.utc(Instant.now()), id);
            } else {
                long alreadyReserved = repository.count("SELECT COALESCE(SUM(r.amount),0) FROM refund r "
                        + "JOIN payment p ON p.payment_id=r.payment_id "
                        + "WHERE p.seller_order_id=? AND r.status<>'REJECTED'", orderId);
                require(amount != null && amount > 0 && amount <= repository.paid(orderId) - alreadyReserved,
                        "INVALID_DISPUTE_AMOUNT");
                repository.update("UPDATE dispute SET status='REFUND_PENDING',decision_type='REFUND',awarded_amount=?,resolution=? WHERE dispute_id=?",
                        amount, resolution, id);
            }
            repository.event(orderId, "DISPUTE_DECIDED", id, Long.toString(actor.userId()),
                    decision + ":" + amount + ":" + resolution);
            notifier.disputeDecided(orderId, id, decision, amount, resolution);
        });
    }

    public void addEvidence(long orderId, long id, String evidence, String commandKey, Actor actor) {
        command(orderId, commandKey, "evidence:" + id + ":" + evidence, actor, () -> {
            access(repository.context(orderId), actor, false);
            require(repository.disputes(orderId).stream().anyMatch(c -> c.id() == id && !"RESOLVED".equals(c.status())), "DISPUTE_NOT_OPEN");
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
        requestRefund(orderId, paymentId, amount, null, reason, policy, commandKey, actor);
    }

    public void requestRefund(long orderId, long paymentId, long amount, Long disputeId, String reason,
            String policy, String commandKey, Actor actor) {
        admin(actor);
        require(policy != null && !policy.isBlank() && policy.length() <= 2000, "REFUND_POLICY_DECISION_REQUIRED");
        command(orderId, commandKey, "refund:" + paymentId + ":" + amount + ":" + disputeId + ":" + reason + ":" + policy, actor, () -> {
            noPayout(orderId);
            if (disputeId != null) {
                var dispute = repository.disputes(orderId).stream().filter(d -> d.id() == disputeId
                        && "REFUND_PENDING".equals(d.status())).findFirst().orElseThrow(
                        () -> new FinanceException(HttpStatus.CONFLICT, "DISPUTE_REFUND_NOT_APPROVED"));
                long allocated = repository.count("SELECT COALESCE(SUM(amount),0) FROM refund "
                        + "WHERE dispute_id=? AND status<>'REJECTED'", disputeId);
                require(amount > 0 && amount <= dispute.amount() - allocated, "REFUND_EXCEEDS_DISPUTE_AWARD");
            } else {
                require(repository.disputes(orderId).stream().noneMatch(d -> !"RESOLVED".equals(d.status())),
                        "DISPUTE_REFUND_LINK_REQUIRED");
            }
            var payment = repository.payment(paymentId);
            require(payment.orderId() == orderId && "SUCCESS".equals(payment.status()) && "HELD".equals(payment.hold()), "PAYMENT_NOT_REFUNDABLE");
            long reserved = repository.count("SELECT COALESCE(SUM(amount),0) FROM refund WHERE payment_id=? AND status<>'REJECTED'", paymentId);
            require(amount > 0 && amount <= payment.amount() - reserved, "REFUND_EXCEEDS_AVAILABLE_AMOUNT");
            holdReadySettlement(orderId, actor);
            // No automatic percentage: an authorized manual policy decision is mandatory and snapshotted.
            long id = repository.insert("""
                    INSERT INTO refund(payment_id,amount,reason,status,requested_at,idempotency_key,policy_snapshot,dispute_id)
                    VALUES(?,?,?,'MANUAL_REVIEW',?,?,?,?)
                    """, paymentId, amount, reason, FinanceRepository.utc(Instant.now()), commandKey, policy, disputeId);
            repository.update("UPDATE refund SET transfer_reference=? WHERE refund_id=?", "MSSREFUND" + id, id);
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
            require(repository.transferDestinations(orderId).stream().anyMatch(t -> "REFUND".equals(t.kind())
                    && t.id() == id && t.bankBin() != null), "REFUND_DESTINATION_REQUIRED");
            repository.update("UPDATE refund SET status='SUCCESS',transfer_reference=?,evidence_url=?,refunded_at=? WHERE refund_id=?",
                    reference, evidence, FinanceRepository.utc(Instant.now()), id);
            repository.ledger(orderId, "refund:" + id, "REFUNDED", refund.amount());
            repository.event(orderId, "REFUND_SUCCESS", id, Long.toString(actor.userId()), reference);
            notifier.refundConfirmed(orderId, id, refund.amount());
            long disputeId = repository.count("SELECT COALESCE(dispute_id,0) FROM refund WHERE refund_id=?", id);
            if (disputeId > 0) {
                var dispute = repository.disputes(orderId).stream().filter(d -> d.id() == disputeId).findFirst().orElseThrow();
                if (repository.refundedForDispute(disputeId) == dispute.amount()) {
                    repository.update("UPDATE dispute SET status='RESOLVED',resolved_at=? WHERE dispute_id=?",
                            FinanceRepository.utc(Instant.now()), disputeId);
                    repository.event(orderId, "DISPUTE_REFUND_COMPLETED", disputeId,
                            Long.toString(actor.userId()), "Award fully refunded");
                }
            }
        });
    }

    public void createSettlement(long orderId, String commandKey, Actor actor) {
        admin(actor);
        command(orderId, commandKey, "settlement", actor, () -> {
            var context = repository.context(orderId);
            require(eligibility(context).eligible(), "SETTLEMENT_NOT_ELIGIBLE");
            var existing = repository.settlements(orderId);
            require(existing.isEmpty() || (existing.size() == 1 && "ON_HOLD".equals(existing.getFirst().status())),
                    "SETTLEMENT_ALREADY_EXISTS");
            long gross = settlementGross(context);
            long net = gross - repository.refunded(orderId);
            require(net > 0, "NOTHING_TO_SETTLE");
            long fee = platformFee(net);
            long id;
            if (existing.isEmpty()) {
                id = repository.insert("""
                        INSERT INTO settlement(seller_order_id,gross_amount,refunded_amount,fee_rate,fee_amount,supplier_payout_amount,
                        status,idempotency_key,fee_basis,rounding_rule) VALUES(?,?,?,5.00,?,?,'READY',?,?,?)
                        """, orderId, gross, repository.refunded(orderId), fee, net - fee,
                        commandKey, feeBasis, rounding);
            } else {
                id = existing.getFirst().settlementId();
                repository.update("""
                        UPDATE settlement SET gross_amount=?,refunded_amount=?,fee_rate=5.00,fee_amount=?,supplier_payout_amount=?,
                        status='READY',idempotency_key=?,fee_basis=?,rounding_rule=? WHERE settlement_id=?
                        """, gross, repository.refunded(orderId), fee, net - fee,
                        commandKey, feeBasis, rounding, id);
            }
            repository.event(orderId, "SETTLEMENT_READY", id, Long.toString(actor.userId()), "feeRate=5;basis=" + feeBasis + ";rounding=" + rounding);
        });
    }

    /** The settlement fee: 5% of the settlement base, rounded with the agreed rule (snapshotted by createSettlement). */
    private long platformFee(long net) {
        return BigDecimal.valueOf(net).multiply(new BigDecimal("0.05"))
                .setScale(0, RoundingMode.valueOf(rounding)).longValueExact();
    }

    public FinanceResponse.SupplierPayouts supplierPayouts(Actor actor, int page, int size) {
        if (actor.supplierId() == null) { throw new FinanceException(HttpStatus.FORBIDDEN, "SUPPLIER_REQUIRED"); }
        if (page < 0 || size < 1 || size > 100) {
            throw new FinanceException(HttpStatus.BAD_REQUEST, "INVALID_FINANCE_REQUEST");
        }
        // Read-only and lock-free. Totals cover every seller order of the supplier; the page is a slice of them.
        var rows = repository.supplierPayouts(actor.supplierId()).stream().map(this::payoutRow).toList();
        long pending = 0;
        long ready = 0;
        long processing = 0;
        long paid = 0;
        for (var row : rows) {
            long amount = row.netPayout() == null ? 0 : row.netPayout();
            switch (row.payoutStatus()) {
                case "PAID" -> paid += amount;
                case "READY" -> ready += amount;
                case "PROCESSING" -> processing += amount;
                case "BLOCKED", "ON_HOLD" -> pending += amount;
                default -> { }
            }
        }
        int from = (int) Math.min((long) page * size, rows.size());
        return new FinanceResponse.SupplierPayouts(rows.subList(from, Math.min(from + size, rows.size())), page, size,
                rows.size(), (rows.size() + size - 1) / size,
                new FinanceResponse.SupplierPayouts.Totals(pending, ready, processing, paid));
    }

    public FinanceResponse.AdminPayouts adminPayouts(Actor actor, String query, int page, int size) {
        admin(actor);
        if (page < 0 || size < 1 || size > 100) {
            throw new FinanceException(HttpStatus.BAD_REQUEST, "INVALID_FINANCE_REQUEST");
        }
        // Read-only and lock-free; only the requested page is evaluated.
        long total = repository.countPayouts(query);
        var rows = repository.payouts(query, (int) Math.min((long) page * size, Integer.MAX_VALUE), size).stream()
                .map(this::payoutRow).toList();
        return new FinanceResponse.AdminPayouts(rows, page, size, total, (int) ((total + size - 1) / size));
    }

    private FinanceResponse.SupplierPayouts.Row payoutRow(FinanceRepository.SupplierPayout order) {
        long id = order.sellerOrderId();
        var context = repository.context(id);
        var eligibility = eligibility(context);
        long collected = repository.paid(id);
        String settlement = order.settlementStatus() == null ? "" : order.settlementStatus();
        // READY/PROCESSING/SUCCESS hold the snapshot that is (or was) paid out; an ON_HOLD one must be recalculated.
        boolean snapshot = List.of("READY", "PROCESSING", "SUCCESS").contains(settlement);
        long refunded = snapshot ? order.refundedAmount() : repository.refunded(id);
        long net = collected - refunded;
        Long fee = snapshot ? order.feeAmount() : net <= 0 ? Long.valueOf(0)
                : List.of("HALF_UP", "DOWN", "UP").contains(rounding) ? Long.valueOf(platformFee(net)) : null;
        Long payout = snapshot ? order.payoutAmount() : fee == null ? null : Math.max(0, net - fee);
        String payoutStatus = switch (settlement) {
            case "SUCCESS" -> "PAID";
            case "PROCESSING", "READY", "ON_HOLD" -> settlement;
            default -> ("CANCELLED".equals(order.status()) && !keptDeposit(id)) || (collected > 0 && net <= 0) ? "NO_PAYOUT"
                    : eligibility.eligible() ? "READY" : "BLOCKED";
        };
        return new FinanceResponse.SupplierPayouts.Row(id, order.sellerOrderNo(), order.orderId(), order.orderNo(),
                order.storeName(), order.status(), order.placedAt(), context.total(), collected,
                refunded, fee, payout, !snapshot, eligibility.eligible(), eligibility.blockingReasons(),
                order.settlementId(), order.settlementStatus(), payoutStatus, order.payoutReference(),
                "SUCCESS".equals(settlement) ? order.settledAt() : null);
    }

    public void beginPayout(long orderId, long id, String commandKey, Actor actor) {
        admin(actor);
        var account = destination(false, repository.context(orderId).supplierId());
        command(orderId, commandKey, "payout:" + id, actor, () -> {
            var context = repository.context(orderId);
            require(eligibility(context).eligible(), "SETTLEMENT_NOT_ELIGIBLE");
            var settlement = repository.settlements(orderId).stream()
                    .filter(s -> s.settlementId() == id && "READY".equals(s.status())).findFirst()
                    .orElseThrow(() -> new FinanceException(HttpStatus.CONFLICT, "SETTLEMENT_NOT_READY"));
            require(settlement.grossAmount() == settlementGross(context)
                    && settlement.refundedAmount() == repository.refunded(orderId)
                    && settlement.grossAmount() == settlement.refundedAmount()
                            + settlement.feeAmount() + settlement.supplierPayoutAmount()
                    && feeBasis.equals(settlement.feeBasis()) && rounding.equals(settlement.roundingRule()),
                    "SETTLEMENT_SNAPSHOT_STALE");
            repository.update("""
                    UPDATE settlement SET status='PROCESSING',payout_reference=?,bank_bin=?,bank_name=?,
                    bank_account_number=?,bank_account_holder=?,bank_snapshot_at=? WHERE settlement_id=?
                    """, "MSSPAYOUT" + id, account.bankBin(), account.bankName(), account.accountNumber(),
                    account.accountHolder(), FinanceRepository.utc(Instant.now()), id);
            repository.event(orderId, "PAYOUT_MANUAL_PROCESSING", id, Long.toString(actor.userId()), "reference=MSSPAYOUT" + id);
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
            require(repository.transferDestinations(orderId).stream().anyMatch(t -> "PAYOUT".equals(t.kind())
                    && t.id() == id && t.bankBin() != null), "PAYOUT_DESTINATION_REQUIRED");
            repository.update("UPDATE settlement SET status='SUCCESS',payout_reference=?,evidence_url=?,settled_at=? WHERE settlement_id=?",
                    reference, evidence, FinanceRepository.utc(Instant.now()), id);
            repository.update("UPDATE payment SET hold_status='RELEASED' WHERE seller_order_id=? AND status='SUCCESS' AND hold_status='HELD'", orderId);
            repository.ledger(orderId, "payout:" + id, "RELEASED", settlement.supplierPayoutAmount());
            if (settlement.feeAmount() > 0) { repository.ledger(orderId, "fee:" + id, "PLATFORM_FEE", settlement.feeAmount()); }
            repository.event(orderId, "SETTLEMENT_SUCCESS", id, Long.toString(actor.userId()), reference);
            repository.update("UPDATE seller_order SET status='SETTLED' WHERE seller_order_id=? "
                    + "AND status IN ('DELIVERED','ACCEPTED')", orderId);
            integration.syncCustomerOrder(orderId);
            notifier.payoutPaid(orderId, id, settlement.supplierPayoutAmount(), reference);
        });
    }
}
