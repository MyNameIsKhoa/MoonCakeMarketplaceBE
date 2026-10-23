package mss.mooncake.rfqnegotiation.service.impl;

import feign.FeignException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import mss.mooncake.rfqnegotiation.client.CatalogServiceClient;
import mss.mooncake.rfqnegotiation.client.dto.catalog.ProductSnapshotResponse;
import mss.mooncake.rfqnegotiation.client.dto.catalog.StoreSummaryResponse;
import mss.mooncake.rfqnegotiation.dto.request.CreateQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.request.QuotationDecisionRequest;
import mss.mooncake.rfqnegotiation.dto.request.QuotationItemRequest;
import mss.mooncake.rfqnegotiation.dto.request.ReviseQuotationRequest;
import mss.mooncake.rfqnegotiation.dto.response.PageResponse;
import mss.mooncake.rfqnegotiation.dto.response.QuotationResponse;
import mss.mooncake.rfqnegotiation.dto.response.QuotationSummaryResponse;
import mss.mooncake.rfqnegotiation.entity.QuotationCommandEntity;
import mss.mooncake.rfqnegotiation.entity.QuotationEntity;
import mss.mooncake.rfqnegotiation.entity.QuotationItemEntity;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import mss.mooncake.rfqnegotiation.enums.QuotationStatus;
import mss.mooncake.rfqnegotiation.enums.RfqStatus;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.repository.QuotationCommandRepository;
import mss.mooncake.rfqnegotiation.repository.QuotationConversionRepository;
import mss.mooncake.rfqnegotiation.repository.QuotationItemRepository;
import mss.mooncake.rfqnegotiation.repository.QuotationRepository;
import mss.mooncake.rfqnegotiation.repository.RfqRepository;
import mss.mooncake.rfqnegotiation.security.ActorContext;
import mss.mooncake.rfqnegotiation.service.QuotationManagementService;
import mss.mooncake.rfqnegotiation.util.Paging;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class QuotationManagementServiceImpl implements QuotationManagementService {
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999999.99");
    private static final Set<String> SORT_FIELDS = Set.of("id", "versionNo", "totalAmount", "validUntil", "submittedAt");
    private final RfqRepository rfqs;
    private final QuotationRepository quotations;
    private final QuotationItemRepository items;
    private final QuotationCommandRepository commands;
    private final QuotationConversionRepository conversions;
    private final CatalogServiceClient catalog;
    private final ActorContext actor;
    private final TransactionTemplate transaction;

    public QuotationManagementServiceImpl(RfqRepository rfqs, QuotationRepository quotations,
            QuotationItemRepository items, QuotationCommandRepository commands,
            QuotationConversionRepository conversions, CatalogServiceClient catalog,
            ActorContext actor, PlatformTransactionManager transactionManager) {
        this.rfqs = rfqs;
        this.quotations = quotations;
        this.items = items;
        this.commands = commands;
        this.conversions = conversions;
        this.catalog = catalog;
        this.actor = actor;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public QuotationResponse create(Long rfqId, String idempotencyKey, CreateQuotationRequest request) {
        Long userId = actor.userId();
        String key = requestKey(idempotencyKey);
        String hash = hash(request);
        Optional<QuotationResponse> replay = replay(userId, "CREATE", rfqId, key, hash);
        if (replay.isPresent()) return replay.get();
        Long supplierId = actor.supplierId();
        PreparedQuotation prepared = prepare(request, supplierId);

        // Remote validation finishes before taking the RFQ lock. All local writes commit together.
        return transaction.execute(tx -> {
            RfqEntity rfq = lockedRfq(rfqId);
            Optional<QuotationResponse> concurrentReplay = replay(userId, "CREATE", rfqId, key, hash);
            if (concurrentReplay.isPresent()) return concurrentReplay.get();
            ensureOpen(rfq);
            var latest = quotations.findFirstByRfqIdAndStoreIdRefOrderByVersionNoDesc(rfqId, request.storeId());
            if (latest.isPresent() && latest.get().getStatus() == QuotationStatus.SUBMITTED) {
                throw ApiException.conflict("QUOTATION_REVISION_REQUIRED", "Cửa hàng đã có báo giá đang gửi; hãy tạo phiên bản mới");
            }
            int version = nextVersion(latest.map(QuotationEntity::getVersionNo).orElse(0));
            return persist(prepared, rfqId, supplierId, version, null, userId, "CREATE", rfqId, key, hash);
        });
    }

    @Override
    public QuotationResponse revise(Long quotationId, String idempotencyKey, ReviseQuotationRequest request) {
        Long userId = actor.userId();
        String key = requestKey(idempotencyKey);
        String hash = hash(request);
        Optional<QuotationResponse> replay = replay(userId, "REVISE", quotationId, key, hash);
        if (replay.isPresent()) return replay.get();
        QuotationEntity initial = requiredQuotation(quotationId);
        actor.assertSupplierOwner(initial.getSupplierIdRef());
        PreparedQuotation prepared = prepare(request.forStore(initial.getStoreIdRef()), initial.getSupplierIdRef());
        return transaction.execute(tx -> {
            RfqEntity rfq = lockedRfq(initial.getRfqId());
            Optional<QuotationResponse> concurrentReplay = replay(userId, "REVISE", quotationId, key, hash);
            if (concurrentReplay.isPresent()) return concurrentReplay.get();
            QuotationEntity previous = lockedQuotation(quotationId);
            ensureOpen(rfq);
            ensureSubmitted(previous);
            if (!Objects.equals(previous.getVersionNo(), request.expectedVersion())) {
                throw ApiException.conflict("QUOTATION_VERSION_CONFLICT", "Phiên bản báo giá đã thay đổi");
            }
            QuotationEntity latest = quotations.findFirstByRfqIdAndStoreIdRefOrderByVersionNoDesc(
                    previous.getRfqId(), previous.getStoreIdRef()).orElseThrow();
            if (!Objects.equals(latest.getId(), previous.getId())) {
                throw ApiException.conflict("QUOTATION_VERSION_CONFLICT", "Chỉ có thể chỉnh sửa phiên bản báo giá mới nhất");
            }
            if (conversions.existsByQuotationIdAndStatusNot(quotationId, "FAILED")) {
                throw ApiException.conflict("QUOTATION_CONVERSION_STARTED", "Báo giá đã bắt đầu chuyển thành đơn hàng");
            }
            previous.setStatus(QuotationStatus.REVISED);
            quotations.save(previous);
            return persist(prepared, previous.getRfqId(), previous.getSupplierIdRef(),
                    nextVersion(previous.getVersionNo()), previous.getId(), userId, "REVISE", quotationId, key, hash);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<QuotationSummaryResponse> listForRfq(Long rfqId, int page, int size, String sort) {
        actor.userId();
        RfqEntity rfq = rfqs.findById(rfqId)
                .orElseThrow(() -> ApiException.notFound("RFQ_NOT_FOUND", "Không tìm thấy yêu cầu báo giá"));
        Specification<QuotationEntity> spec = (root, query, cb) -> cb.equal(root.get("rfqId"), rfqId);
        if (!Objects.equals(actor.optionalCustomerId(), rfq.getCustomerIdRef())) {
            Long supplierId = actor.supplierId();
            spec = spec.and((root, query, cb) -> cb.equal(root.get("supplierIdRef"), supplierId));
        }
        return PageResponse.from(quotations.findAll(spec, Paging.page(page, size, sort, SORT_FIELDS, "submittedAt"))
                .map(QuotationSummaryResponse::from));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<QuotationSummaryResponse> listMine(Long rfqId, Long storeId, QuotationStatus status,
                                                          int page, int size, String sort) {
        Long supplierId = actor.supplierId();
        Specification<QuotationEntity> spec = (root, query, cb) -> cb.equal(root.get("supplierIdRef"), supplierId);
        if (rfqId != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("rfqId"), rfqId));
        if (storeId != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("storeIdRef"), storeId));
        // EXPIRED is derived from SUBMITTED + validUntil, matching the status reported in responses.
        LocalDateTime now = now();
        if (status == QuotationStatus.EXPIRED || status == QuotationStatus.SUBMITTED) {
            spec = spec.and((root, query, cb) -> cb.and(cb.equal(root.get("status"), QuotationStatus.SUBMITTED),
                    status == QuotationStatus.EXPIRED ? cb.lessThanOrEqualTo(root.get("validUntil"), now)
                            : cb.greaterThan(root.get("validUntil"), now)));
        } else if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return PageResponse.from(quotations.findAll(spec, Paging.page(page, size, sort, SORT_FIELDS, "submittedAt"))
                .map(QuotationSummaryResponse::from));
    }

    @Override
    @Transactional(readOnly = true)
    public QuotationResponse get(Long quotationId) {
        actor.userId();
        QuotationEntity quotation = requiredQuotation(quotationId);
        RfqEntity rfq = rfqs.findById(quotation.getRfqId())
                .orElseThrow(() -> ApiException.notFound("RFQ_NOT_FOUND", "Không tìm thấy yêu cầu báo giá"));
        if (!Objects.equals(actor.optionalCustomerId(), rfq.getCustomerIdRef())) {
            actor.assertSupplierOwner(quotation.getSupplierIdRef());
        }
        return response(quotation);
    }

    @Override
    public QuotationResponse reject(Long quotationId, QuotationDecisionRequest request) {
        QuotationEntity quotation = requiredQuotation(quotationId);
        RfqEntity rfq = rfqs.findById(quotation.getRfqId())
                .orElseThrow(() -> ApiException.notFound("RFQ_NOT_FOUND", "Không tìm thấy yêu cầu báo giá"));
        actor.assertCustomerOwner(rfq.getCustomerIdRef());
        return decide(quotation, QuotationStatus.REJECTED, request);
    }

    @Override
    public QuotationResponse cancel(Long quotationId, QuotationDecisionRequest request) {
        QuotationEntity quotation = requiredQuotation(quotationId);
        actor.assertSupplierOwner(quotation.getSupplierIdRef());
        return decide(quotation, QuotationStatus.CANCELLED, request);
    }

    private QuotationResponse decide(QuotationEntity initial, QuotationStatus destination, QuotationDecisionRequest request) {
        String reason = text(request == null ? null : request.reason());
        if (reason != null && reason.length() > 2000) {
            throw ApiException.badRequest("DECISION_REASON_TOO_LONG", "Lý do tối đa 2000 ký tự");
        }
        return transaction.execute(tx -> {
            lockedRfq(initial.getRfqId());
            QuotationEntity quotation = lockedQuotation(initial.getId());
            if (quotation.getStatus() == destination) {
                if (!Objects.equals(reason, quotation.getDecisionReason())) {
                    throw ApiException.conflict("QUOTATION_DECISION_CONFLICT", "Lý do khác với quyết định đã lưu");
                }
                return response(quotation);
            }
            ensureSubmitted(quotation);
            if (conversions.existsByQuotationIdAndStatusNot(quotation.getId(), "FAILED")) {
                throw ApiException.conflict("QUOTATION_CONVERSION_STARTED", "Báo giá đã bắt đầu chuyển thành đơn hàng");
            }
            quotation.setStatus(destination);
            quotation.setDecisionReason(reason);
            quotations.save(quotation);
            return response(quotation);
        });
    }

    private PreparedQuotation prepare(CreateQuotationRequest request, Long supplierId) {
        if (request.storeId() == null || request.storeId() <= 0) {
            throw ApiException.badRequest("STORE_REQUIRED", "Cần chọn cửa hàng hợp lệ");
        }
        StoreSummaryResponse store;
        try {
            store = catalog.getStoreSummary(request.storeId());
        } catch (FeignException.NotFound ex) {
            throw ApiException.notFound("STORE_NOT_FOUND", "Không tìm thấy cửa hàng");
        } catch (FeignException ex) {
            throw ApiException.unavailable("CATALOG_UNAVAILABLE", "Không xác minh được cửa hàng");
        }
        if (store == null || !Objects.equals(request.storeId(), store.storeId())) {
            throw ApiException.unavailable("CATALOG_INVALID_RESPONSE", "Dữ liệu cửa hàng không hợp lệ");
        }
        if (!Objects.equals(store.supplierIdRef(), supplierId)) {
            throw ApiException.forbidden("STORE_NOT_OWNED", "Cửa hàng không thuộc nhà cung cấp này");
        }
        if (!"ACTIVE".equals(store.status())) {
            throw ApiException.conflict("STORE_NOT_ACTIVE", "Cửa hàng chưa hoạt động");
        }
        if (request.validUntil() == null || request.proposedDeliveryDate() == null) {
            throw ApiException.badRequest("QUOTATION_TERMS_REQUIRED", "Cần thời hạn báo giá và ngày giao dự kiến");
        }
        if (request.leadTimeDays() != null && request.leadTimeDays() < 0) {
            throw ApiException.badRequest("INVALID_LEAD_TIME", "Thời gian chuẩn bị không được âm");
        }
        BigDecimal deposit = decimal(request.depositPercent(), "depositPercent", new BigDecimal("100.00"));
        BigDecimal shippingFee = money(request.shippingFee(), "shippingFee");
        if (request.items() == null || request.items().isEmpty() || request.items().size() > 50) {
            throw ApiException.badRequest("INVALID_QUOTATION_ITEMS", "Báo giá cần từ 1 đến 50 mục hàng");
        }
        List<QuotationItemEntity> lines = new ArrayList<>();
        Map<String, ProductSnapshotResponse> snapshots = new HashMap<>();
        BigDecimal total = shippingFee;
        for (QuotationItemRequest line : request.items()) {
            QuotationItemEntity item = prepareItem(line, store, snapshots);
            lines.add(item);
            total = money(total.add(item.getSubtotal()), "totalAmount");
        }
        QuotationEntity quotation = new QuotationEntity();
        quotation.setStoreIdRef(store.storeId());
        quotation.setDepositPercent(deposit);
        quotation.setShippingFee(shippingFee);
        quotation.setTotalAmount(total);
        quotation.setValidUntil(request.validUntil().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS));
        quotation.setProposedDeliveryDate(request.proposedDeliveryDate());
        quotation.setLeadTimeDays(request.leadTimeDays());
        quotation.setShippingMethod(text(request.shippingMethod()));
        quotation.setPaymentTerms(text(request.paymentTerms()));
        return new PreparedQuotation(quotation, lines);
    }

    private QuotationItemEntity prepareItem(QuotationItemRequest line, StoreSummaryResponse store,
                                            Map<String, ProductSnapshotResponse> snapshots) {
        if (line == null || line.quantity() == null || line.quantity() <= 0) {
            throw ApiException.badRequest("INVALID_ITEM_QUANTITY", "Số lượng mục hàng phải lớn hơn 0");
        }
        String name = text(line.itemNameSnapshot());
        String sku = text(line.skuSnapshot());
        if (line.variantId() != null) {
            if (line.variantId() <= 0 || line.productId() == null || line.productId() <= 0) {
                throw ApiException.badRequest("PRODUCT_REQUIRED_FOR_VARIANT", "Cần productId hợp lệ khi chọn biến thể");
            }
            String snapshotKey = line.productId() + ":" + line.variantId();
            ProductSnapshotResponse snapshot = snapshots.computeIfAbsent(snapshotKey,
                    key -> productSnapshot(line.productId(), line.variantId()));
            if (snapshot == null || !Objects.equals(snapshot.productId(), line.productId())
                    || !Objects.equals(snapshot.variantId(), line.variantId())
                    || !Objects.equals(snapshot.storeId(), store.storeId())
                    || !Objects.equals(snapshot.supplierId(), store.supplierIdRef())) {
                throw ApiException.badRequest("VARIANT_STORE_MISMATCH", "Biến thể phải thuộc đúng sản phẩm và cửa hàng báo giá");
            }
            if (!"ACTIVE".equals(snapshot.status())) {
                throw ApiException.conflict("VARIANT_NOT_ACTIVE", "Sản phẩm hoặc biến thể không được bán");
            }
            name = text(snapshot.productName());
            if (name == null) {
                throw ApiException.unavailable("CATALOG_INVALID_RESPONSE", "Catalog chưa cung cấp tên sản phẩm hợp lệ");
            }
            if (text(snapshot.variantName()) != null) name = name + " - " + snapshot.variantName().strip();
            sku = text(snapshot.sku());
        } else if (line.productId() != null) {
            throw ApiException.badRequest("VARIANT_REQUIRED_FOR_PRODUCT", "Mục sản phẩm cần chọn biến thể; mục tùy chỉnh bỏ cả hai ID");
        }
        if (name == null || name.length() > 255 || (sku != null && sku.length() > 100)) {
            throw ApiException.badRequest("INVALID_ITEM_SNAPSHOT", "Tên mục hàng bắt buộc, tối đa 255 ký tự; SKU tối đa 100 ký tự");
        }
        BigDecimal unitPrice = money(line.unitPrice(), "unitPrice");
        BigDecimal gross = money(unitPrice.multiply(BigDecimal.valueOf(line.quantity())), "grossAmount");
        BigDecimal discount = money(line.discountAmount() == null ? BigDecimal.ZERO : line.discountAmount(), "discountAmount");
        if (discount.compareTo(gross) > 0) {
            throw ApiException.badRequest("DISCOUNT_EXCEEDS_LINE_AMOUNT", "Giảm giá không được vượt giá trị mục hàng");
        }
        QuotationItemEntity item = new QuotationItemEntity();
        item.setVariantIdRef(line.variantId());
        item.setItemNameSnapshot(name);
        item.setSkuSnapshot(sku);
        item.setQuantity(line.quantity());
        item.setUnitPrice(unitPrice);
        item.setDiscountAmount(discount);
        item.setSubtotal(gross.subtract(discount));
        item.setCustomizationNote(text(line.customizationNote()));
        return item;
    }

    private ProductSnapshotResponse productSnapshot(Long productId, Long variantId) {
        try {
            return catalog.getProductSnapshot(productId, variantId);
        } catch (FeignException.NotFound ex) {
            throw ApiException.badRequest("VARIANT_NOT_FOUND", "Không tìm thấy biến thể thuộc sản phẩm đã chọn");
        } catch (FeignException ex) {
            throw ApiException.unavailable("CATALOG_UNAVAILABLE", "Không xác minh được biến thể");
        }
    }

    private QuotationResponse persist(PreparedQuotation prepared, Long rfqId, Long supplierId,
            int version, Long previousId, Long userId, String type, Long targetId, String key, String hash) {
        QuotationEntity quotation = prepared.quotation();
        LocalDateTime now = now();
        if (!quotation.getValidUntil().isAfter(now)) {
            throw ApiException.badRequest("QUOTATION_VALIDITY_EXPIRED", "Thời hạn báo giá phải ở tương lai");
        }
        if (quotation.getProposedDeliveryDate().isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw ApiException.badRequest("INVALID_DELIVERY_DATE", "Ngày giao dự kiến không được ở quá khứ");
        }
        quotation.setRfqId(rfqId);
        quotation.setSupplierIdRef(supplierId);
        quotation.setPreviousQuotationId(previousId);
        quotation.setVersionNo(version);
        quotation.setStatus(QuotationStatus.SUBMITTED);
        quotation.setSubmittedAt(now);
        quotations.saveAndFlush(quotation);
        prepared.items().forEach(item -> item.setQuotationId(quotation.getId()));
        List<QuotationItemEntity> savedItems = items.saveAll(prepared.items());
        commands.save(new QuotationCommandEntity(userId, type, targetId, key, hash, quotation.getId()));
        return QuotationResponse.from(quotation, savedItems);
    }

    private Optional<QuotationResponse> replay(Long userId, String type, Long targetId, String key, String hash) {
        return commands.findByActorUserIdAndCommandTypeAndTargetIdAndRequestKey(userId, type, targetId, key)
                .map(command -> {
                    if (!command.getRequestHash().equals(hash)) {
                        throw ApiException.conflict("IDEMPOTENCY_KEY_CONFLICT", "Khóa gửi lặp đã dùng với dữ liệu khác");
                    }
                    return response(requiredQuotation(command.getQuotationId()));
                });
    }

    private QuotationResponse response(QuotationEntity quotation) {
        return QuotationResponse.from(quotation, items.findByQuotationIdOrderByIdAsc(quotation.getId()));
    }

    private QuotationEntity requiredQuotation(Long id) {
        return quotations.findById(id)
                .orElseThrow(() -> ApiException.notFound("QUOTATION_NOT_FOUND", "Không tìm thấy báo giá"));
    }

    private QuotationEntity lockedQuotation(Long id) {
        return quotations.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("QUOTATION_NOT_FOUND", "Không tìm thấy báo giá"));
    }

    private RfqEntity lockedRfq(Long id) {
        return rfqs.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("RFQ_NOT_FOUND", "Không tìm thấy yêu cầu báo giá"));
    }

    private void ensureOpen(RfqEntity rfq) {
        if (rfq.getStatus() != RfqStatus.OPEN) {
            throw ApiException.conflict("RFQ_NOT_OPEN", "Yêu cầu báo giá đã ngừng nhận báo giá");
        }
        if (rfq.getExpiresAt() != null && !rfq.getExpiresAt().isAfter(now())) {
            throw ApiException.conflict("RFQ_EXPIRED", "Yêu cầu báo giá đã hết hạn");
        }
    }

    private void ensureSubmitted(QuotationEntity quotation) {
        if (quotation.getStatus() != QuotationStatus.SUBMITTED) {
            throw ApiException.conflict("QUOTATION_NOT_SUBMITTED", "Chỉ có thể xử lý báo giá đang gửi");
        }
    }

    private int nextVersion(int previousVersion) {
        if (previousVersion == Integer.MAX_VALUE) {
            throw ApiException.conflict("QUOTATION_VERSION_LIMIT", "Đã đạt giới hạn phiên bản báo giá");
        }
        return previousVersion + 1;
    }

    private static BigDecimal money(BigDecimal value, String field) {
        BigDecimal amount = decimal(value, field, MAX_MONEY);
        // Commerce payments (TV5, payOS) are whole VND, so a quotation cannot carry fractional dong.
        if (amount.remainder(BigDecimal.ONE).signum() != 0) {
            throw ApiException.badRequest("AMOUNT_NOT_WHOLE_VND", field + " phải là số tiền nguyên đồng (VND)");
        }
        return amount;
    }

    private static BigDecimal decimal(BigDecimal value, String field, BigDecimal max) {
        if (value == null || value.signum() < 0 || value.compareTo(max) > 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", field + " không hợp lệ hoặc vượt giới hạn lưu trữ");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ApiException.badRequest("INVALID_AMOUNT_SCALE", field + " chỉ có tối đa 2 chữ số thập phân");
        }
    }

    private static String requestKey(String value) {
        if (value == null || value.isBlank() || value.length() > 100 || !value.matches("[!-~]+")) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key cần từ 1 đến 100 ký tự ASCII không có khoảng trắng");
        }
        return value;
    }

    private static String hash(CreateQuotationRequest request) {
        return fingerprint(null, request);
    }

    private static String hash(ReviseQuotationRequest request) {
        return fingerprint(request.expectedVersion(), request.forStore(null));
    }

    private static String fingerprint(Integer expectedVersion, CreateQuotationRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digestFields(digest, expectedVersion, request.storeId(), request.depositPercent(),
                    request.validUntil() == null ? null : request.validUntil().toInstant(), request.leadTimeDays(),
                    request.proposedDeliveryDate(), request.shippingMethod(), request.shippingFee(), request.paymentTerms());
            digestFields(digest, request.items() == null ? null : request.items().size());
            if (request.items() != null) {
                for (QuotationItemRequest item : request.items()) {
                    if (item == null) {
                        digestFields(digest, (Object) null);
                    } else {
                        digestFields(digest, item.productId(), item.variantId(), item.itemNameSnapshot(), item.skuSnapshot(),
                                item.quantity(), item.unitPrice(), item.discountAmount(), item.customizationNote());
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void digestFields(MessageDigest digest, Object... fields) {
        for (Object value : fields) {
            if (value == null) {
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            } else {
                String canonical = value instanceof BigDecimal decimal ? decimal.stripTrailingZeros().toPlainString() : value.toString();
                byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
        }
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    private record PreparedQuotation(QuotationEntity quotation, List<QuotationItemEntity> items) {
    }
}
