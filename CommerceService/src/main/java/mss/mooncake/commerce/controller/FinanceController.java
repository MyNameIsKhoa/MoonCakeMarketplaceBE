package mss.mooncake.commerce.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import mss.mooncake.commerce.dto.response.FinanceResponse;
import mss.mooncake.commerce.exception.FinanceException;
import mss.mooncake.commerce.service.FinanceService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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
            @NotBlank @Size(max = 2000) String policySnapshot) { }
    public record Proof(@NotBlank @Size(max = 255) String reference,
            @NotBlank @Size(max = 1000) @Pattern(regexp = "https://[^\\s]+") String evidenceUrl) { }
    public record Evidence(@NotBlank @Size(max = 1000) @Pattern(regexp = "https://[^\\s]+") String evidenceUrl) { }
    private final FinanceService service;

    public FinanceController(FinanceService service) { this.service = service; }

    private FinanceService.Actor actor(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof Number id)) {
            throw new FinanceException(HttpStatus.UNAUTHORIZED, "AUTHENTICATED_USER_ID_REQUIRED");
        }
        var roles = authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList();
        return new FinanceService.Actor(id.longValue(), roles.contains("ROLE_ADMIN"), roles.contains("ROLE_SUPPLIER"));
    }

    @GetMapping("/orders/{orderId}")
    public FinanceResponse summary(@PathVariable long orderId, Authentication authentication) {
        return service.summary(orderId, actor(authentication));
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

    @PostMapping("/orders/{orderId}/disputes/{id}/resolve")
    public FinanceResponse resolve(@PathVariable long orderId, @PathVariable long id, @Valid @RequestBody Reason body,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.resolveDispute(orderId, id, body.reason(), key, actor);
        return service.summary(orderId, actor);
    }

    @PostMapping("/orders/{orderId}/refunds")
    public FinanceResponse refund(@PathVariable long orderId, @Valid @RequestBody Refund body,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.requestRefund(orderId, body.paymentId(), body.amount(), body.reason(), body.policySnapshot(), key, actor);
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

    @PostMapping("/orders/{orderId}/settlements/{id}/confirm")
    public FinanceResponse confirmPayout(@PathVariable long orderId, @PathVariable long id, @Valid @RequestBody Proof body,
            @RequestHeader("Idempotency-Key") String key, Authentication authentication) {
        var actor = actor(authentication);
        service.confirmPayout(orderId, id, body.reference(), body.evidenceUrl(), key, actor);
        return service.summary(orderId, actor);
    }
}
