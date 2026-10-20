package mss.mooncake.catalogstore.service.impl;

import mss.mooncake.catalogstore.dto.request.CreateVariantRequest;
import mss.mooncake.catalogstore.dto.request.UpdateVariantRequest;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.dto.response.VariantResponse;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.enums.ProductStatus;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ConflictException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.service.ProductVariantService;
import mss.mooncake.catalogstore.service.StoreAccessService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class ProductVariantServiceImpl implements ProductVariantService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final StoreAccessService storeAccessService;

    public ProductVariantServiceImpl(ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            StoreAccessService storeAccessService) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.storeAccessService = storeAccessService;
    }

    // ─── Helpers
    // ──────────────────────────────────────────────────────────────────

    /** Map variant → VariantResponse. */
    private VariantResponse toVariantResponse(ProductVariantEntity v) {
        return new VariantResponse(v.getId(), v.getProductId(), v.getSku(), v.getVariantName(), v.getImageUrl(),
                v.getBasePrice(), v.getStockQuantity(), v.getStatus().name());
    }

    private ProductEntity requireProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm với id: " + productId));
    }

    private ProductVariantEntity requireVariant(Long productId, Long variantId) {
        return variantRepository.findByIdAndProductId(variantId, productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy biến thể " + variantId + " của sản phẩm " + productId));
    }

    private boolean isOwner(ProductEntity product, Long userId) {
        return userId != null && storeAccessService.isOwner(product.getStoreId(), userId);
    }

    /** Product ACTIVE thuộc store ACTIVE thì người ngoài mới xem được variant. */
    private boolean isPublished(ProductEntity product) {
        return product.getStatus() == ProductStatus.ACTIVE && storeAccessService.isPublic(product.getStoreId());
    }

    private ProductStatus parseStatus(String status) {
        try {
            return ProductStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Trạng thái biến thể không hợp lệ: " + status);
        }
    }

    /**
     * Cùng quy tắc với product: DRAFT → ACTIVE/INACTIVE, ACTIVE ↔ INACTIVE; không
     * về DRAFT, không đặt tay OUT_OF_STOCK.
     * Không cho tắt variant ACTIVE cuối cùng khi product đang ACTIVE (product mở
     * bán phải có hàng để bán).
     */
    private void validateStatusTransition(ProductEntity product, ProductVariantEntity variant, ProductStatus next) {
        ProductStatus current = variant.getStatus();
        if (current == next)
            return;
        if (next == ProductStatus.DRAFT || next == ProductStatus.OUT_OF_STOCK) {
            throw new BadRequestException("Cannot change variant status from " + current + " to " + next);
        }
        if (current == ProductStatus.ACTIVE
                && product.getStatus() == ProductStatus.ACTIVE
                && !variantRepository.existsByProductIdAndStatusAndIdNot(product.getId(), ProductStatus.ACTIVE,
                        variant.getId())) {
            throw new BadRequestException("Không thể tắt biến thể ACTIVE cuối cùng của sản phẩm đang mở bán. "
                    + "Hãy chuyển sản phẩm sang INACTIVE trước.");
        }
    }

    /**
     * Flush ngay để bắt vi phạm UQ_product_variant_sku khi hai request cùng dùng
     * một SKU.
     */
    private ProductVariantEntity saveUniqueSku(ProductVariantEntity variant) {
        try {
            return variantRepository.saveAndFlush(variant);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("SKU đã tồn tại: " + variant.getSku());
        }
    }

    // ─── Public API
    // ───────────────────────────────────────────────────────────────

    @Override
    public PageResponse<VariantResponse> getVariants(Long productId, Long userId, Pageable pageable) {
        ProductEntity product = requireProduct(productId);
        if (isOwner(product, userId)) {
            return PageResponse.of(variantRepository.findByProductId(productId, pageable),
                    this::toVariantResponse);
        }
        if (!isPublished(product)) {
            throw new ResourceNotFoundException("Không tìm thấy sản phẩm với id: " + productId);
        }
        return PageResponse.of(variantRepository.findByProductIdAndStatus(productId, ProductStatus.ACTIVE, pageable),
                this::toVariantResponse);
    }

    @Override
    public VariantResponse getVariant(Long productId, Long variantId, Long userId) {
        ProductEntity product = requireProduct(productId);
        ProductVariantEntity variant = requireVariant(productId, variantId);
        boolean visible = isOwner(product, userId)
                || (isPublished(product) && variant.getStatus() == ProductStatus.ACTIVE);
        if (!visible) {
            throw new ResourceNotFoundException("Không tìm thấy biến thể " + variantId + " của sản phẩm " + productId);
        }
        return toVariantResponse(variant);
    }

    @Override
    @Transactional
    public VariantResponse createVariant(Long productId, CreateVariantRequest request, Long userId) {
        ProductEntity product = requireProduct(productId);
        storeAccessService.requireOwnedStore(product.getStoreId(), userId);

        String sku = request.sku().trim();
        if (variantRepository.existsBySku(sku)) {
            throw new ConflictException("SKU đã tồn tại: " + sku);
        }

        ProductVariantEntity variant = new ProductVariantEntity();
        variant.setProductId(productId);
        variant.setSku(sku);
        variant.setVariantName(request.variantName().trim());
        variant.setImageUrl(request.imageUrl());
        variant.setBasePrice(request.basePrice());
        variant.setStockQuantity(request.stockQuantity());
        variant.setStatus(ProductStatus.DRAFT);
        return toVariantResponse(saveUniqueSku(variant));
    }

    @Override
    @Transactional
    public VariantResponse updateVariant(Long productId, Long variantId, UpdateVariantRequest request, Long userId) {
        ProductEntity product = requireProduct(productId);
        ProductVariantEntity variant = requireVariant(productId, variantId);
        storeAccessService.requireOwnedStore(product.getStoreId(), userId);

        if (request.sku() != null) {
            String sku = request.sku().trim();
            if (!StringUtils.hasText(sku))
                throw new BadRequestException("SKU không được để trống.");
            if (variantRepository.existsBySkuAndIdNot(sku, variantId)) {
                throw new ConflictException("SKU đã tồn tại: " + sku);
            }
            variant.setSku(sku);
        }
        if (request.variantName() != null) {
            if (!StringUtils.hasText(request.variantName()))
                throw new BadRequestException("Tên biến thể không được để trống.");
            variant.setVariantName(request.variantName().trim());
        }
        if (request.imageUrl() != null) {
            variant.setImageUrl(request.imageUrl());
        }
        if (request.basePrice() != null) {
            variant.setBasePrice(request.basePrice());
        }
        // Tồn kho đặt giá trị tuyệt đối từ màn quản lý của supplier.
        // Trừ kho khi checkout sẽ dùng UPDATE có điều kiện riêng (chốt với TV3), không
        // đi qua API này.
        if (request.stockQuantity() != null) {
            variant.setStockQuantity(request.stockQuantity());
        }
        if (request.status() != null) {
            ProductStatus next = parseStatus(request.status());
            validateStatusTransition(product, variant, next);
            variant.setStatus(next);
        }
        return toVariantResponse(saveUniqueSku(variant));
    }
}
