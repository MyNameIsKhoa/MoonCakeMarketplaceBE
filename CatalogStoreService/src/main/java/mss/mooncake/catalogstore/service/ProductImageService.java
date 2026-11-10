package mss.mooncake.catalogstore.service;

import mss.mooncake.catalogstore.dto.response.ProductImageResponse;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface ProductImageService {
    ProductImageResponse upload(Long productId, Long userId, MultipartFile file);

    Resource load(String filename);
}
