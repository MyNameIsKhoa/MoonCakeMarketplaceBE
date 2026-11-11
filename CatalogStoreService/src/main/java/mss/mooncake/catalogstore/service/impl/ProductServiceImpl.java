package mss.mooncake.catalogstore.service.impl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import mss.mooncake.catalogstore.dto.request.CreateProductRequest;
import mss.mooncake.catalogstore.dto.request.UpdateProductRequest;
import mss.mooncake.catalogstore.dto.response.PageResponse;
import mss.mooncake.catalogstore.dto.response.ProductResponse;
import mss.mooncake.catalogstore.dto.response.ProductSummaryResponse;
import mss.mooncake.catalogstore.dto.response.VariantResponse;
import mss.mooncake.catalogstore.entity.ProductEntity;
import mss.mooncake.catalogstore.entity.ProductVariantEntity;
import mss.mooncake.catalogstore.enums.CategoryStatus;
import mss.mooncake.catalogstore.enums.ProductStatus;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ConflictException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.CategoryRepository;
import mss.mooncake.catalogstore.repository.ProductRepository;
import mss.mooncake.catalogstore.repository.ProductVariantRepository;
import mss.mooncake.catalogstore.repository.StockReservationLineRepository;
import mss.mooncake.catalogstore.service.ProductService;
import mss.mooncake.catalogstore.service.StoreAccessService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final CategoryRepository categoryRepository;
    private final StoreAccessService storeAccessService;
    private final StockReservationLineRepository reservationLines;
    private final Clock clock;

    public ProductServiceImpl(ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            CategoryRepository categoryRepository,
            StoreAccessService storeAccessService, StockReservationLineRepository reservationLines, Clock clock) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.categoryRepository = categoryRepository;
        this.storeAccessService = storeAccessService;
        this.reservationLines = reservationLines;
        this.clock = clock;
    }

    // ─── Helpers
    // ──────────────────────────────────────────────────────────────────

    /** Map variant → VariantResponse. */
    private VariantResponse toVariantResponse(ProductVariantEntity v, Map<Long, Long> held) {
        return new VariantResponse(v.getId(), v.getProductId(), v.getSku(), v.getVariantName(), v.getImageUrl(),
                v.getBasePrice(), v.getStockQuantity(), available(v, held), v.getStatus().name());
    }

    /** Map product + variants → ProductResponse. */
    private ProductResponse toProductResponse(ProductEntity p, List<ProductVariantEntity> variants) {
        var held = heldQuantities(variants);
        return new ProductResponse(p.getId(), p.getStoreId(), p.getCategoryId(), p.getProductCode(), p.getName(),
                p.getDescription(), p.getStatus().name(), p.getCreatedAt(), p.getUpdatedAt(),
                variants.stream().map(v -> toVariantResponse(v, held)).toList());
    }

    private Map<Long, Long> heldQuantities(List<ProductVariantEntity> variants) {
        if (variants.isEmpty()) return Map.of();
        return reservationLines.heldQuantities(variants.stream().map(ProductVariantEntity::getId).toList(),
                LocalDateTime.now(clock)).stream().collect(Collectors.toMap(
                        StockReservationLineRepository.HeldQuantity::getVariantId,
                        StockReservationLineRepository.HeldQuantity::getQuantity));
    }

    private long available(ProductVariantEntity variant, Map<Long, Long> held) {
        return Math.max(0, variant.getStockQuantity() - held.getOrDefault(variant.getId(), 0L));
    }

    private ProductSummaryResponse toSummary(ProductEntity p, List<ProductVariantEntity> variants,
                                             Map<Long, Long> held) {
        List<ProductVariantEntity> active = variants.stream()
                .filter(v -> v.getStatus() == ProductStatus.ACTIVE)
                .toList();
        BigDecimal minPrice = active.stream()
                .filter(v -> available(v, held) > 0)
                .map(ProductVariantEntity::getBasePrice)
                .min(Comparator.naturalOrder())
                .orElse(null);
        String imageUrl = active.stream()
                .map(ProductVariantEntity::getImageUrl)
                .filter(url -> url != null && !url.isBlank())
                .findFirst()
                .orElse(null);
        return new ProductSummaryResponse(p.getId(), p.getStoreId(), p.getCategoryId(), p.getProductCode(),
                p.getName(), p.getStatus().name(), minPrice, imageUrl, p.getStatus() == ProductStatus.ACTIVE && minPrice != null);
    }

    /** Sinh product_code unique trong store dạng "PRD-XXXXXXXX". */
    private String generateUniqueProductCode(Long storeId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "PRD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            if (!productRepository.existsByStoreIdAndProductCode(storeId, code))
                return code;
        }
        throw new ConflictException("Không tạo được mã sản phẩm duy nhất, vui lòng thử lại");
    }

    private void requireActiveCategory(Long categoryId) {
        if (categoryRepository.findByIdAndStatus(categoryId, CategoryStatus.ACTIVE).isEmpty()) {
            throw new BadRequestException("Danh mục không tồn tại hoặc đã ngừng hoạt động: " + categoryId);
        }
    }

    private ProductEntity requireProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm với id: " + productId));
    }

    private List<ProductVariantEntity> variantsOf(Long productId) {
        return variantRepository.findByProductIdInOrderByIdAsc(List.of(productId));
    }

    private ProductStatus parseStatus(String status) {
        try {
            return ProductStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Trạng thái sản phẩm không hợp lệ: " + status);
        }
    }

    /**
     * DRAFT → ACTIVE/INACTIVE, ACTIVE ↔ INACTIVE; không quay lại DRAFT.
     * OUT_OF_STOCK không đặt tay được (hết hàng suy ra từ tồn kho variant).
     * Lên ACTIVE cần ít nhất một variant ACTIVE.
     */
    private void validateStatusTransition(ProductEntity product, ProductStatus next) {
        ProductStatus current = product.getStatus();
        if (current == next)
            return;
        boolean allowed = switch (next) {
            case ACTIVE, INACTIVE -> true;
            case DRAFT, OUT_OF_STOCK -> false;
        };
        if (!allowed) {
            throw new BadRequestException("Không thể chuyển trạng thái sản phẩm từ " + current + " sang " + next);
        }
        if (next == ProductStatus.ACTIVE
                && !variantRepository.existsByProductIdAndStatus(product.getId(), ProductStatus.ACTIVE)) {
            throw new BadRequestException("Sản phẩm cần ít nhất một biến thể ACTIVE trước khi mở bán.");
        }
    }

    // ─── Public API
    // ───────────────────────────────────────────────────────────────

    @Override
    public PageResponse<ProductSummaryResponse> searchProducts(String q, Long storeId, Long categoryId,
            boolean mine, Long userId, Pageable pageable) {
        Page<ProductEntity> page = mine
                ? productRepository.findAll(ProductRepository.ownerSearch(
                        storeAccessService.findOwnedStoreIds(userId), q, storeId, categoryId), pageable)
                : productRepository.findAll(ProductRepository.publicSearch(q, storeId, categoryId), pageable);

        // Load variant của cả trang một lần để tránh N+1.
        List<Long> productIds = page.getContent().stream().map(ProductEntity::getId).toList();
        Map<Long, List<ProductVariantEntity>> variantsByProduct = productIds.isEmpty()
                ? Map.of()
                : variantRepository.findByProductIdInOrderByIdAsc(productIds).stream()
                        .collect(Collectors.groupingBy(ProductVariantEntity::getProductId));

        var held = heldQuantities(variantsByProduct.values().stream().flatMap(List::stream).toList());
        return PageResponse.of(page,
                p -> toSummary(p, variantsByProduct.getOrDefault(p.getId(), List.of()), held));
    }

    @Override
    public ProductResponse getProduct(Long productId, Long userId) {
        ProductEntity product = requireProduct(productId);
        boolean owner = userId != null && storeAccessService.isOwner(product.getStoreId(), userId);
        boolean published = product.getStatus() == ProductStatus.ACTIVE
                && storeAccessService.isPublic(product.getStoreId());
        if (!owner && !published) {
            // Không tiết lộ sự tồn tại của sản phẩm chưa công bố.
            throw new ResourceNotFoundException("Không tìm thấy sản phẩm với id: " + productId);
        }

        List<ProductVariantEntity> variants = variantsOf(productId);
        if (!owner) {
            variants = variants.stream().filter(v -> v.getStatus() == ProductStatus.ACTIVE).toList();
        }
        return toProductResponse(product, variants);
    }

    @Override
    @Transactional
    public ProductResponse createProduct(CreateProductRequest request, Long userId) {
        // 1. Owner store (Feign Identity) — kiểm tra TRƯỚC khi ghi DB
        storeAccessService.requireOwnedStore(request.storeId(), userId);
        // 2. Category phải ACTIVE
        requireActiveCategory(request.categoryId());

        // 3. Lưu product ở DRAFT với mã do server sinh
        ProductEntity product = new ProductEntity();
        product.setStoreId(request.storeId());
        product.setCategoryId(request.categoryId());
        product.setProductCode(generateUniqueProductCode(request.storeId()));
        product.setName(request.name().trim());
        product.setDescription(request.description());
        product.setStatus(ProductStatus.DRAFT);

        product = productRepository.save(product);
        return toProductResponse(product, List.of());
    }

    @Override
    @Transactional
    public ProductResponse updateProduct(Long productId, UpdateProductRequest request, Long userId) {
        ProductEntity product = requireProduct(productId);
        storeAccessService.requireOwnedStore(product.getStoreId(), userId);

        if (request.categoryId() != null) {
            requireActiveCategory(request.categoryId());
            product.setCategoryId(request.categoryId());
        }
        if (request.name() != null) {
            if (!StringUtils.hasText(request.name()))
                throw new BadRequestException("Tên sản phẩm không được để trống.");
            product.setName(request.name().trim());
        }
        if (request.description() != null) {
            product.setDescription(request.description());
        }
        if (request.status() != null) {
            ProductStatus next = parseStatus(request.status());
            validateStatusTransition(product, next);
            product.setStatus(next);
        }

        // Flush để @PreUpdate cập nhật updatedAt trước khi trả response.
        product = productRepository.saveAndFlush(product);
        return toProductResponse(product, variantsOf(productId));
    }
}
