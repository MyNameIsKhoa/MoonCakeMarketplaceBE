package mss.mooncake.catalogstore.controller;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import mss.mooncake.catalogstore.dto.request.CreatePromotionRequest;
import mss.mooncake.catalogstore.dto.request.PromotionRuleRequest;
import mss.mooncake.catalogstore.dto.request.UpdatePromotionRequest;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.dto.response.PromotionResponse;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.security.UserPrincipal;
import mss.mooncake.catalogstore.service.PromotionService;
import mss.mooncake.catalogstore.dto.request.PageQuery;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public API cho Promotion (TV2).
 *
 * GET   /api/promotions                       — CA16 public (ACTIVE, chưa kết thúc, store ACTIVE); ?mine=true cho supplier
 * GET   /api/promotions/{promotionId}         — CA17 public; chưa công bố chỉ owner xem được
 * POST  /api/promotions                       — CA18 supplier owner, tạo DRAFT kèm rules
 * PATCH /api/promotions/{promotionId}         — CA19 name/startAt/endAt/status
 * PUT   /api/promotions/{promotionId}/rules   — CA20 thay toàn bộ rules
 */
@RestController
@RequestMapping("/api/promotions")
public class PromotionController {

    private static final Set<String> SORT_FIELDS = Set.of("startAt", "endAt", "name", "id");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "startAt");

    private final PromotionService promotionService;
    private final Validator validator;

    public PromotionController(PromotionService promotionService, Validator validator) {
        this.promotionService = promotionService;
        this.validator = validator;
    }

    private static Long userIdOf(UserPrincipal principal) {
        return principal == null ? null : principal.getUserId();
    }

    /**
     * Body của CA20 là mảng JSON nên @Valid không áp dụng cho từng phần tử;
     * validate tay để lỗi field trả 400 thay vì lọt xuống service.
     */
    private void validateEach(List<PromotionRuleRequest> rules) {
        if (rules == null) throw new BadRequestException("Body phải là mảng điều kiện khuyến mãi.");
        String errors = IntStream.range(0, rules.size())
                .filter(i -> rules.get(i) != null)
                .boxed()
                .flatMap(i -> validator.validate(rules.get(i)).stream()
                        .map((ConstraintViolation<PromotionRuleRequest> v) ->
                                "rules[" + i + "]." + v.getPropertyPath() + ": " + v.getMessage()))
                .collect(Collectors.joining("; "));
        if (!errors.isEmpty()) throw new BadRequestException(errors);
    }

    @GetMapping
    public ResponseEntity<PageResponse<PromotionResponse>> getPromotions(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            PageQuery pageQuery,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (mine && principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(promotionService.getPromotions(storeId, mine, userIdOf(principal),
                pageQuery.toPageable(SORT_FIELDS, DEFAULT_SORT)));
    }

    @GetMapping("/{promotionId}")
    public ResponseEntity<PromotionResponse> getPromotion(@PathVariable Long promotionId,
                                                          @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(promotionService.getPromotion(promotionId, userIdOf(principal)));
    }

    @PostMapping
    public ResponseEntity<PromotionResponse> createPromotion(@Valid @RequestBody CreatePromotionRequest request,
                                                             @AuthenticationPrincipal UserPrincipal principal) {
        PromotionResponse response = promotionService.createPromotion(request, principal.getUserId());
        return ResponseEntity.created(URI.create("/api/promotions/" + response.promotionId())).body(response);
    }

    @PatchMapping("/{promotionId}")
    public ResponseEntity<PromotionResponse> updatePromotion(@PathVariable Long promotionId,
                                                             @Valid @RequestBody UpdatePromotionRequest request,
                                                             @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(promotionService.updatePromotion(promotionId, request, principal.getUserId()));
    }

    @PutMapping("/{promotionId}/rules")
    public ResponseEntity<PromotionResponse> replaceRules(@PathVariable Long promotionId,
                                                          @RequestBody List<PromotionRuleRequest> rules,
                                                          @AuthenticationPrincipal UserPrincipal principal) {
        validateEach(rules);
        return ResponseEntity.ok(promotionService.replaceRules(promotionId, rules, principal.getUserId()));
    }
}
