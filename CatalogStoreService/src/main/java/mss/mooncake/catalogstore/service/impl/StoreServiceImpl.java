package mss.mooncake.catalogstore.service.impl;

import feign.FeignException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import mss.mooncake.catalogstore.client.IdentityClient;
import mss.mooncake.catalogstore.client.dto.SupplierSummaryDto;
import mss.mooncake.catalogstore.dto.request.CreateStoreRequest;
import mss.mooncake.catalogstore.dto.request.UpdateStoreRequest;
import mss.mooncake.catalogstore.dto.response.StoreResponse;
import mss.mooncake.catalogstore.dto.response.StoreSummaryResponse;
import mss.mooncake.catalogstore.entity.StoreEntity;
import mss.mooncake.catalogstore.enums.StoreStatus;
import mss.mooncake.catalogstore.exception.BadRequestException;
import mss.mooncake.catalogstore.exception.ConflictException;
import mss.mooncake.catalogstore.exception.ForbiddenException;
import mss.mooncake.catalogstore.exception.ResourceNotFoundException;
import mss.mooncake.catalogstore.repository.StoreRepository;
import mss.mooncake.catalogstore.service.StoreService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class StoreServiceImpl implements StoreService {

    /** Mã lỗi 409 khi chuyển store sang ACTIVE mà chưa có phí vận chuyển. */
    public static final String SHIPPING_FEE_REQUIRED = "STORE_SHIPPING_FEE_REQUIRED";

    private final StoreRepository storeRepository;
    private final IdentityClient identityClient;

    public StoreServiceImpl(StoreRepository storeRepository, IdentityClient identityClient) {
        this.storeRepository = storeRepository;
        this.identityClient = identityClient;
    }

    // ─── Helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Chuyển tên store thành slug: lowercase, thay khoảng trắng thành '-', bỏ ký tự
     * đặc biệt. Đ/đ không phải dấu kết hợp (NFD không tách được) nên đổi tay sang d.
     */
    private String generateSlug(String name) {
        String normalized = Normalizer.normalize(name.replace('Đ', 'D').replace('đ', 'd'), Normalizer.Form.NFD);
        String ascii = Pattern.compile("\\p{InCombiningDiacriticalMarks}+").matcher(normalized).replaceAll("");
        return ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    /** Đảm bảo slug unique — thêm suffix ngẫu nhiên nếu trùng. */
    private String ensureUniqueSlug(String base) {
        String slug = base;
        while (storeRepository.existsBySlug(slug)) {
            slug = base + "-" + UUID.randomUUID().toString().substring(0, 6);
        }
        return slug;
    }

    /** Sinh store_code unique dạng "STR-XXXXXXXX". */
    private String generateUniqueStoreCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "STR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
            if (!storeRepository.existsByStoreCode(code))
                return code;
        }
        throw new ConflictException("Không tạo được mã cửa hàng duy nhất, vui lòng thử lại");
    }

    /**
     * Lấy SupplierSummary của userId; ném BadRequestException nếu chưa có hồ sơ
     * supplier.
     */
    private SupplierSummaryDto requireSupplier(Long userId) {
        try {
            return identityClient.getSupplierSummaryByUserId(userId);
        } catch (FeignException.NotFound e) {
            throw new BadRequestException("User chưa đăng ký hồ sơ nhà cung cấp (supplier). " +
                    "Vui lòng hoàn tất supplier onboarding trước khi tạo store.");
        } catch (FeignException e) {
            throw new RuntimeException("Không thể kết nối Identity Service để xác minh supplier: " + e.getMessage(), e);
        }
    }

    /** Lấy SupplierSummary và kiểm tra trạng thái kích hoạt (ACTIVE/VERIFIED). */
    private SupplierSummaryDto requireActiveSupplier(Long userId) {
        SupplierSummaryDto supplier = requireSupplier(userId);
        String status = supplier.verificationStatus();
        if (status == null || !"VERIFIED".equalsIgnoreCase(status)) {
            throw new BadRequestException(
                    "Hồ sơ nhà cung cấp chưa được xác minh hoặc kích hoạt (trạng thái: " + status + "). " +
                            "Vui lòng chờ phê duyệt trước khi thao tác cửa hàng.");
        }
        return supplier;
    }

    /** Map entity → StoreResponse. */
    private StoreResponse toResponse(StoreEntity e) {
        return new StoreResponse(
                e.getId(),
                e.getSupplierIdRef(),
                e.getStoreCode(),
                e.getName(),
                e.getSlug(),
                e.getLogoUrl(),
                e.getBannerUrl(),
                e.getThemeKey(),
                e.getStatus().name(),
                e.getDescription(),
                e.getShippingFee());
    }

    // ─── Public API
    // ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public StoreResponse createStore(CreateStoreRequest request, Long userId) {
        // 1. Xác minh supplier qua Identity và phải ACTIVE/VERIFIED — Feign call TRƯỚC
        // khi ghi DB
        SupplierSummaryDto supplier = requireActiveSupplier(userId);

        // 2. Sinh store_code và slug duy nhất; tên không có ký tự Latin (vd. "月饼") cho slug rỗng → dùng store code
        String storeCode = generateUniqueStoreCode();
        String baseSlug = generateSlug(request.name());
        String slug = ensureUniqueSlug(baseSlug.isEmpty() ? storeCode.toLowerCase(Locale.ROOT) : baseSlug);

        // 3. Lưu store
        StoreEntity store = new StoreEntity();
        store.setSupplierIdRef(supplier.supplierId());
        store.setStoreCode(storeCode);
        store.setName(request.name());
        store.setSlug(slug);
        store.setLogoUrl(request.logoUrl());
        store.setBannerUrl(request.bannerUrl());
        store.setThemeKey(request.themeKey());
        store.setDescription(request.description());
        store.setShippingFee(request.shippingFee());
        store.setStatus(StoreStatus.DRAFT); // Trạng thái ban đầu là DRAFT

        store = storeRepository.save(store);
        return toResponse(store);
    }

    @Override
    public List<StoreResponse> getMyStores(Long userId) {
        // Lấy supplierId của userId từ Identity để query store
        SupplierSummaryDto supplier = requireSupplier(userId);
        return storeRepository.findBySupplierIdRef(supplier.supplierId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public List<StoreResponse> getAllStores() {
        return storeRepository.findByStatusOrderByIdAsc(StoreStatus.ACTIVE)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public List<StoreResponse> getAllStoresForAdmin() {
        return storeRepository.findAll(org.springframework.data.domain.Sort.by("id")).stream()
                .map(this::toResponse).toList();
    }

    @Override
    public List<StoreResponse> getStoresBySupplierId(Long supplierId) {
        return storeRepository.findBySupplierIdRefAndStatusOrderByIdAsc(supplierId, StoreStatus.ACTIVE)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public StoreResponse getStoreById(Long storeId) {
        StoreEntity store = storeRepository.findByIdAndStatus(storeId, StoreStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy store với id: " + storeId));
        return toResponse(store);
    }

    @Override
    public StoreResponse getStoreByIdOrSlug(String identifier, Long userId) {
        if (!StringUtils.hasText(identifier)) {
            throw new BadRequestException("Identifier của store không được để trống.");
        }
        String trimmed = identifier.trim();
        StoreEntity store = null;
        // Nếu là số, thử tìm theo store_id trước
        if (trimmed.matches("^\\d+$")) {
            try {
                store = storeRepository.findById(Long.parseLong(trimmed)).orElse(null);
            } catch (NumberFormatException ignored) {
                // Numeric slug can exceed Long.MAX_VALUE.
            }
        }
        if (store == null) {
            store = storeRepository.findBySlug(trimmed)
                    .orElseThrow(
                            () -> new ResourceNotFoundException("Không tìm thấy store với id hoặc slug: " + trimmed));
        }
        if (store.getStatus() != StoreStatus.ACTIVE) {
            if (userId == null) {
                throw new ResourceNotFoundException("Không tìm thấy store với id hoặc slug: " + trimmed);
            }
            try {
                SupplierSummaryDto supplier = identityClient.getSupplierSummaryByUserId(userId);
                if (!store.getSupplierIdRef().equals(supplier.supplierId())) {
                    throw new ResourceNotFoundException("Không tìm thấy store với id hoặc slug: " + trimmed);
                }
            } catch (FeignException.NotFound e) {
                throw new ResourceNotFoundException("Không tìm thấy store với id hoặc slug: " + trimmed);
            }
        }
        return toResponse(store);
    }

    @Override
    @Transactional
    public StoreResponse updateStore(Long storeId, UpdateStoreRequest request, Long userId) {
        return updateStore(storeId, request, userId, false);
    }

    @Override
    @Transactional
    public StoreResponse updateStore(Long storeId, UpdateStoreRequest request, Long userId, boolean admin) {
        // 1. Tìm store
        StoreEntity store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy store với id: " + storeId));

        // 2. Xác minh supplier của user hiện tại (phải ACTIVE/VERIFIED)
        SupplierSummaryDto supplier = admin ? null : requireActiveSupplier(userId);

        // 3. Kiểm tra ownership: store.supplierIdRef phải = supplier.supplierId của
        // user đang login
        if (!admin && !store.getSupplierIdRef().equals(supplier.supplierId())) {
            throw new ForbiddenException("Bạn không có quyền chỉnh sửa store này.");
        }
        // Giống branch/product: store bị admin tạm khóa thì owner không được sửa gì (kể cả đổi trạng thái).
        if (!admin && store.getStatus() == StoreStatus.SUSPENDED) {
            throw new ForbiddenException("Store đang bị tạm khóa, không thể chỉnh sửa. Vui lòng liên hệ quản trị viên.");
        }

        // 4. Patch từng field nếu có giá trị
        if (StringUtils.hasText(request.name())) {
            store.setName(request.name());
        }
        if (request.logoUrl() != null) {
            store.setLogoUrl(request.logoUrl());
        }
        if (request.bannerUrl() != null) {
            store.setBannerUrl(request.bannerUrl());
        }
        if (request.themeKey() != null) {
            store.setThemeKey(request.themeKey());
        }
        if (request.description() != null) {
            store.setDescription(request.description());
        }
        if (request.shippingFee() != null) {
            store.setShippingFee(request.shippingFee());
        }
        if (StringUtils.hasText(request.status())) {
            StoreStatus next;
            try {
                next = StoreStatus.valueOf(request.status().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("Trạng thái store không hợp lệ: " + request.status());
            }
            validateStatusTransition(store.getStatus(), next, admin);
            // Checkout tính phí ship theo store: không công bố store chưa cấu hình phí (shippingFee đã patch ở trên).
            if (next == StoreStatus.ACTIVE && store.getStatus() != StoreStatus.ACTIVE && store.getShippingFee() == null) {
                throw new ConflictException(SHIPPING_FEE_REQUIRED,
                        "Cần cấu hình phí vận chuyển (shippingFee) trước khi mở bán store.");
            }
            store.setStatus(next);
        }

        store = storeRepository.save(store);
        return toResponse(store);
    }

    private void validateStatusTransition(StoreStatus current, StoreStatus next, boolean admin) {
        if (current == next)
            return;
        if (!admin && (current == StoreStatus.SUSPENDED || next == StoreStatus.SUSPENDED)) {
            throw new ForbiddenException("Chỉ quản trị viên mới có thể tạm khóa hoặc mở khóa cửa hàng");
        }
        if (admin && next == StoreStatus.SUSPENDED
                && (current == StoreStatus.ACTIVE || current == StoreStatus.INACTIVE))
            return;
        boolean allowed = switch (current) {
            case DRAFT -> next == StoreStatus.ACTIVE || next == StoreStatus.INACTIVE;
            case ACTIVE -> next == StoreStatus.INACTIVE;
            case INACTIVE -> next == StoreStatus.ACTIVE;
            case SUSPENDED -> admin && (next == StoreStatus.ACTIVE || next == StoreStatus.INACTIVE);
        };
        if (!allowed)
            throw new BadRequestException(
                    "Cannot change store status from " + current + " to " + next);
    }

    // ─── Internal contract
    // ────────────────────────────────────────────────────────

    @Override
    public StoreSummaryResponse getStoreSummary(Long storeId) {
        StoreEntity store = storeRepository.findById(storeId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy store với id: " + storeId));
        return new StoreSummaryResponse(
                store.getId(),
                store.getSupplierIdRef(),
                store.getStoreCode(),
                store.getName(),
                store.getSlug(),
                store.getLogoUrl(),
                store.getStatus().name(),
                store.getShippingFee());
    }
}
