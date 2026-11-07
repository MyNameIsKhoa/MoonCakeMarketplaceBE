package mss.mooncake.commerce.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import mss.mooncake.commerce.dto.request.CreateReviewRequest;
import mss.mooncake.commerce.dto.response.ReviewResponse;
import mss.mooncake.commerce.security.CommercePrincipal;
import mss.mooncake.commerce.service.FulfillmentApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final FulfillmentApplicationService fulfillmentService;

    public ReviewController(FulfillmentApplicationService fulfillmentService) {
        this.fulfillmentService = fulfillmentService;
    }

    @PostMapping
    public ResponseEntity<ReviewResponse> createReview(
            @Valid @RequestBody CreateReviewRequest request,
            @AuthenticationPrincipal CommercePrincipal principal) {
        Long customerId = principal != null && principal.customerId() != null
                ? principal.customerId()
                : null;
        ReviewResponse response = fulfillmentService.createReview(request, customerId);
        return ResponseEntity.created(URI.create("/api/reviews/" + response.reviewId())).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ReviewResponse>> getReviews(
            @RequestParam(required = false) Long variantId,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            @AuthenticationPrincipal CommercePrincipal principal) {
        if (mine) {
            Long customerId = principal != null && principal.customerId() != null
                    ? principal.customerId()
                    : null;
            return ResponseEntity.ok(fulfillmentService.getMyReviews(customerId));
        }
        if (storeId != null) {
            return ResponseEntity.ok(fulfillmentService.getReviewsByStore(storeId));
        }
        return ResponseEntity.ok(fulfillmentService.getReviewsByVariant(variantId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReviewResponse> getReviewById(@PathVariable Long id) {
        return ResponseEntity.ok(fulfillmentService.getReviewById(id));
    }
}
