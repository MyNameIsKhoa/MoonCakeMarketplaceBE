package mss.mooncake.catalogstore.controller;

import java.net.URI;
import java.time.Duration;
import mss.mooncake.catalogstore.dto.response.ProductImageResponse;
import mss.mooncake.catalogstore.security.UserPrincipal;
import mss.mooncake.catalogstore.service.ProductImageService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ProductImageController {
    private final ProductImageService images;

    public ProductImageController(ProductImageService images) {
        this.images = images;
    }

    @PostMapping(value = "/api/products/{productId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProductImageResponse> upload(@PathVariable Long productId,
            @RequestParam("file") MultipartFile file, @AuthenticationPrincipal UserPrincipal principal) {
        var response = images.upload(productId, principal.getUserId(), file);
        return ResponseEntity.created(URI.create(response.url())).body(response);
    }

    @GetMapping("/uploads/catalog/{filename}")
    public ResponseEntity<Resource> image(@PathVariable String filename) {
        Resource image = images.load(filename);
        return ResponseEntity.ok()
                .contentType(filename.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(image);
    }
}
