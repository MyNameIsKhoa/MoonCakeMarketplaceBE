package mss.mooncake.catalogstore.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.Set;
import mss.mooncake.catalogstore.dto.request.CreateProductRequest;
import mss.mooncake.catalogstore.dto.request.UpdateProductRequest;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.dto.response.ProductResponse;
import mss.mooncake.catalogstore.dto.response.ProductSummaryResponse;
import mss.mooncake.catalogstore.security.UserPrincipal;
import mss.mooncake.catalogstore.service.ProductService;
import mss.mooncake.catalogstore.dto.request.PageQuery;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public API cho Product (TV2).
 *
 * GET   /api/products                — CA06 search public; ?mine=true lấy product của supplier (yêu cầu JWT)
 * GET   /api/products/{productId}    — CA07 public; product chưa công bố chỉ owner xem được
 * POST  /api/products                — CA08 supplier owner của store
 * PATCH /api/products/{productId}    — CA09 supplier owner của store
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final Set<String> SORT_FIELDS = Set.of("name", "createdAt", "id");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    private static Long userIdOf(UserPrincipal principal) {
        return principal == null ? null : principal.getUserId();
    }

    @GetMapping
    public ResponseEntity<PageResponse<ProductSummaryResponse>> searchProducts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            PageQuery pageQuery,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (mine && principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(productService.searchProducts(q, storeId, categoryId, mine, userIdOf(principal),
                pageQuery.toPageable(SORT_FIELDS, DEFAULT_SORT)));
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable Long productId,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(productService.getProduct(productId, userIdOf(principal)));
    }

    /** userId lấy từ JWT principal, không nhận từ body. */
    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request,
                                                         @AuthenticationPrincipal UserPrincipal principal) {
        ProductResponse response = productService.createProduct(request, principal.getUserId());
        return ResponseEntity.created(URI.create("/api/products/" + response.productId())).body(response);
    }

    @PatchMapping("/{productId}")
    public ResponseEntity<ProductResponse> updateProduct(@PathVariable Long productId,
                                                         @Valid @RequestBody UpdateProductRequest request,
                                                         @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(productService.updateProduct(productId, request, principal.getUserId()));
    }
}
