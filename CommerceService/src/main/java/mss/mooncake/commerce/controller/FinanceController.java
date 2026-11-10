package mss.mooncake.commerce.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import mss.mooncake.commerce.dto.response.FinanceResponse;
import mss.mooncake.commerce.dto.response.CustomerPaymentResponse;
import mss.mooncake.commerce.dto.response.TransferInstructionResponse;
import mss.mooncake.commerce.service.FinanceService;
import mss.mooncake.commerce.service.CustomerPaymentService;
import mss.mooncake.commerce.service.FinanceActorResolver;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.payos.model.webhooks.Webhook;

@RestController
@RequestMapping("/api/payments")
public class FinanceController {
    public record Reason(@NotBlank @Size(max = 500) String reason) { }
    public record Dispute(@NotBlank @Size(max = 500) String reason,
            @Size(max = 1000) @Pattern(regexp = "https://[^\\s]+") String evidenceUrl) { }
    public record Decision(boolean approve, @NotBlank @Size(max = 1000) String reason) { }
    public record Refund(@Positive long paymentId, @Positive long amount,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Size(max = 2000) String policySnapshot, @Positive Long disputeId) { }
    public record DisputeDecision(@NotBlank String decision, @Positive Long amount,
            @NotBlank @Size(max = 1000) String reason) { }
    public record Proof(@NotBlank @Size(max = 255) String reference,
            @NotBlank @Size(max = 1000) @Pattern(regexp = "https://[^\\s]+") String evidenceUrl) { }
    public record Evidence(@NotBlank @Size(max = 1000) @Pattern(regexp = "https://[^\\s]+") String evidenceUrl) { }
    private final FinanceService service;
    private final FinanceActorResolver actors;
    private final CustomerPaymentService customerPayments;

    @Autowired
    public FinanceController(FinanceService service, FinanceActorResolver actors, CustomerPaymentService customerPayments) {
        this.service = service;
        this.actors = actors;
        this.customerPayments = customerPayments;
    }

    public FinanceController(FinanceService service, FinanceActorResolver actors) {
        this(service, actors, null);
    }

    private FinanceService.Actor actor(Authentication authentication) {
        return actors.resolve(authentication);
    }

    @GetMapping("/orders/by-number")
    public FinanceResponse summaryByNumber(@RequestParam @NotBlank @Size(max = 100) String sellerOrderNo,
                                           Authentication authentication) {
        return service.summaryByNumber(sellerOrderNo, actor(authentication));
    }

    @GetMapping("/orders/{orderId}")
    public FinanceResponse summary(@PathVariable long orderId, Authentication authentication) {
        return service.summary(orderId, actor(authentication));
    }

    @GetMapping("/orders/{orderId}/transfer-instructions")
    public TransferInstructionResponse transferInstructions(@PathVariable long orderId, Authentication authentication) {
        return service.transferInstructions(orderId, actor(authentication));
    }

    /** Supplier view "Doanh thu & giải ngân": own seller orders' settlement state plus totals. */
    @GetMapping("/settlements/mine")
    public FinanceResponse.SupplierPayouts mySettlements(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication authentication) {
        return service.supplierPayouts(actor(authentication), page, size);
    }

    @GetMapping("/customer-orders/{orderId}")
    public CustomerPaymentResponse customerSummary(@PathVariable long orderId, Authentication authentication) {
        return customerPayments.summary(orderId, actor(authentication));
    }

    @PostMapping("/customer-orders/{orderId}/initiate")
    public CustomerPaymentResponse initiateCustomer(@PathVariable long orderId,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        return customerPayments.initiate(orderId, key, actor(authentication));
    }

    @PostMapping("/customer-orders/{orderId}/reconcile")
    public CustomerPaymentResponse reconcileCustomer(@PathVariable long orderId, Authentication authentication) {
        return customerPayments.reconcile(orderId, actor(authentication), false);
    }

    @PostMapping("/customer-orders/{orderId}/cancel-link")
    public CustomerPaymentResponse cancelCustomerLink(@PathVariable long orderId, Authentication authentication) {
        return customerPayments.reconcile(orderId, actor(authentication), true);
    }

    @PostMapping("/customer-orders/{orderId}/cancel-unpaid")
    public java.util.Map<String, String> cancelUnpaidCustomerOrder(@PathVariable long orderId,
            @Valid @RequestBody Reason body, Authentication authentication) {
        customerPayments.cancelUnpaidOrder(orderId, body.reason(), actor(authentication));
        return java.util.Map.of("status", "CANCELLED");
    }

    @PostMapping("/{paymentId}/initiate")
    public FinanceResponse.Payment initiate(@PathVariable long paymentId,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        return service.initiate(paymentId, key, actor(authentication));
    }

    @PostMapping("/{paymentId}/reconcile")
    public FinanceResponse.Payment reconcile(@PathVariable long paymentId, Authentication authentication) {
        return service.reconcile(paymentId, actor(authentication), false);
    }

    @PostMapping("/{paymentId}/cancel-link")
    public FinanceResponse.Payment cancelLink(@PathVariable long paymentId, Authentication authentication) {
        return service.reconcile(paymentId, actor(authentication), true);
    }

    @PostMapping("/payos/callback")
    public java.util.Map<String, String> webhook(@RequestBody Webhook body) {
        service.webhook(body);
        return java.util.Map.of("code", "00", "desc", "success");
    }

    @PostMapping("/orders/{orderId}/cancellations")
    public FinanceResponse cancel(@PathVariable long orderId, @Valid @RequestBody Reason body,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.requestCancellation(orderId, body.reason(), key, actor);
        return service.summary(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/cancellations/{id}/decision")
    public FinanceResponse decide(@PathVariable long orderId, @PathVariable long id,
            @Valid @RequestBody Decision body, @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.decideCancellation(orderId, id, body.approve(), body.reason(), key, actor);
        return service.summary(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/disputes")
    public FinanceResponse dispute(@PathVariable long orderId, @Valid @RequestBody Dispute body,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.openDispute(orderId, body.reason(), body.evidenceUrl(), key, actor);
        return service.summary(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/disputes/{id}/respond")
    public FinanceResponse respondDispute(@PathVariable long orderId, @PathVariable long id,
            @Valid @RequestBody Reason body, @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        var actor = actor(authentication);
        service.respondDispute(orderId, id, body.reason(), key, actor);
        return service.summary(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/disputes/{id}/escalate")
    public FinanceResponse escalateDispute(@PathVariable long orderId, @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.escalateDispute(orderId, id, key, actor);
        return service.summary(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/disputes/{id}/decision")
    public FinanceResponse decideDispute(@PathVariable long orderId, @PathVariable long id,
            @Valid @RequestBody DisputeDecision body, @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        var actor = actor(authentication);
        service.decideDispute(orderId, id, body.decision(), body.amount(), body.reason(), key, actor);
        return service.summary(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/refunds")
    public FinanceResponse refund(@PathVariable long orderId, @Valid @RequestBody Refund body,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.requestRefund(orderId, body.paymentId(), body.amount(), body.disputeId(),
                body.reason(), body.policySnapshot(), key, actor);
        return service.summary(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/disputes/{id}/evidence")
    public FinanceResponse evidence(@PathVariable long orderId, @PathVariable long id, @Valid @RequestBody Evidence body,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.addEvidence(orderId, id, body.evidenceUrl(), key, actor);
        return service.summary(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/refunds/{id}/reject")
    public FinanceResponse rejectRefund(@PathVariable long orderId, @PathVariable long id, @Valid @RequestBody Reason body,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.rejectRefund(orderId, id, body.reason(), key, actor);
        return service.summary(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/refunds/{id}/prepare-transfer")
    public TransferInstructionResponse prepareRefundTransfer(@PathVariable long orderId, @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.prepareRefundTransfer(orderId, id, key, actor);
        return service.transferInstructions(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/refunds/{id}/confirm")
    public FinanceResponse confirmRefund(@PathVariable long orderId, @PathVariable long id, @Valid @RequestBody Proof body,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.confirmRefund(orderId, id, body.reference(), body.evidenceUrl(), key, actor);
        return service.summary(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/settlements")
    public FinanceResponse settlement(@PathVariable long orderId, @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {
        var actor = actor(authentication);
        service.createSettlement(orderId, key, actor);
        return service.summary(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/settlements/{id}/payout")
    public FinanceResponse payout(@PathVariable long orderId, @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.beginPayout(orderId, id, key, actor);
        return service.summary(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/settlements/{id}/prepare-transfer")
    public TransferInstructionResponse preparePayoutTransfer(@PathVariable long orderId, @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.preparePayoutTransfer(orderId, id, key, actor);
        return service.transferInstructions(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/settlements/{id}/confirm")
    public FinanceResponse confirmPayout(@PathVariable long orderId, @PathVariable long id, @Valid @RequestBody Proof body,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.confirmPayout(orderId, id, body.reference(), body.evidenceUrl(), key, actor);
        return service.summary(orderId, actor);
    }
}
