package mss.mooncake.commerce.controller;

import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import mss.mooncake.commerce.dto.response.FinanceResponse;
import mss.mooncake.commerce.exception.FinanceException;
import mss.mooncake.commerce.repository.FinanceRepository;
import mss.mooncake.commerce.service.FinanceActorResolver;
import mss.mooncake.commerce.service.FinanceService;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/admin/commerce/disputes")
public class AdminDisputeController {
    private final FinanceRepository repository;
    private final FinanceService finance;
    private final FinanceActorResolver actors;

    public AdminDisputeController(FinanceRepository repository, FinanceService finance, FinanceActorResolver actors) {
        this.repository = repository;
        this.finance = finance;
        this.actors = actors;
    }

    public record DisputeOrder(long sellerOrderId, FinanceResponse summary) { }
    public record DisputePage(List<DisputeOrder> content, int page, boolean hasMore) { }

    @GetMapping
    public DisputePage pending(@RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size, Authentication authentication) {
        var actor = actors.resolve(authentication);
        if (!actor.admin()) { throw new FinanceException(HttpStatus.FORBIDDEN, "FINANCE_ADMIN_REQUIRED"); }
        var ids = repository.pendingDisputeOrderIds(page * size, size + 1);
        return new DisputePage(ids.stream().limit(size)
                .map(id -> new DisputeOrder(id, finance.summary(id, actor))).toList(),
                page, ids.size() > size);
    }
}
