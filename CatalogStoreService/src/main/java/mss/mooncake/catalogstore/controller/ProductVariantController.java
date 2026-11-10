package mss.mooncake.catalogstore.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.Set;
import mss.mooncake.catalogstore.dto.request.CreateVariantRequest;
import mss.mooncake.catalogstore.dto.request.UpdateVariantRequest;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.dto.response.VariantResponse;
import mss.mooncake.catalogstore.security.UserPrincipal;
import mss.mooncake.catalogstore.service.ProductVariantService;
import mss.mooncake.catalogstore.dto.request.PageQuery;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public API cho Variant (TV2).
 *
 * GET /api/products/{productId}/variants — CA11 public; owner thấy mọi trạng
 * thái
 * GET /api/products/{productId}/variants/{variantId} — CA12 public; owner thấy
 * mọi trạng thái
 * POST /api/products/{productId}/variants — CA13 supplier owner của store
 * PATCH /api/products/{productId}/variants/{variantId} — CA14 supplier owner
 * của store
 */
@RestController
@RequestMapping("/api")
public class ProductVariantController {

    private static final Set<String> SORT_FIELDS = Set.of("basePrice", "variantName", "id");
    private static final Sort DEFAULT_SORT = Sort.by("id");

    private final ProductVariantService variantService;

    public ProductVariantController(ProductVariantService variantService) {
        this.variantService = variantService;
    }

    private static Long userIdOf(UserPrincipal principal) {
        return principal == null ? null : principal.getUserId();
    }

    @GetMapping("/products/{productId}/variants")
    public ResponseEntity<PageResponse<VariantResponse>> getVariants(
            @PathVariable Long productId,
            PageQuery pageQuery,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(variantService.getVariants(productId, userIdOf(principal),
                pageQuery.toPageable(SORT_FIELDS, DEFAULT_SORT)));
    }

    @GetMapping("/products/{productId}/variants/{variantId}")
    public ResponseEntity<VariantResponse> getVariant(@PathVariable Long productId,
            @PathVariable Long variantId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(variantService.getVariant(productId, variantId, userIdOf(principal)));
    }

    @GetMapping("/stores/{storeId}/variants/{variantId}")
    public VariantResponse getOwnedVariant(@PathVariable Long storeId, @PathVariable Long variantId,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED);
        return variantService.getOwnedVariant(storeId, variantId, principal.getUserId());
    }

    @PostMapping("/products/{productId}/variants")
    public ResponseEntity<VariantResponse> createVariant(@PathVariable Long productId,
            @Valid @RequestBody CreateVariantRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        VariantResponse response = variantService.createVariant(productId, request, principal.getUserId());
        return ResponseEntity.created(URI.create("/api/products/" + productId + "/variants/" + response.variantId()))
                .body(response);
    }

    @PatchMapping("/products/{productId}/variants/{variantId}")
    public ResponseEntity<VariantResponse> updateVariant(@PathVariable Long productId,
            @PathVariable Long variantId,
            @Valid @RequestBody UpdateVariantRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(variantService.updateVariant(productId, variantId, request, principal.getUserId()));
    }
}
