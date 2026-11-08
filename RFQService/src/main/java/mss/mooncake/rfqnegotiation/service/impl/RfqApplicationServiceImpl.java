package mss.mooncake.rfqnegotiation.service.impl;

import feign.FeignException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import mss.mooncake.rfqnegotiation.client.CatalogServiceClient;
import mss.mooncake.rfqnegotiation.client.dto.catalog.StoreSummaryResponse;
import mss.mooncake.rfqnegotiation.dto.request.CreateRfqRequest;
import mss.mooncake.rfqnegotiation.dto.response.PageResponse;
import mss.mooncake.rfqnegotiation.dto.response.RfqResponse;
import mss.mooncake.rfqnegotiation.entity.QuotationEntity;
import mss.mooncake.rfqnegotiation.entity.RfqEntity;
import mss.mooncake.rfqnegotiation.entity.RfqTargetStoreEntity;
import mss.mooncake.rfqnegotiation.enums.QuotationStatus;
import mss.mooncake.rfqnegotiation.enums.RfqAudience;
import mss.mooncake.rfqnegotiation.enums.RfqStatus;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.notification.RfqNotifier;
import mss.mooncake.rfqnegotiation.repository.QuotationRepository;
import mss.mooncake.rfqnegotiation.repository.RfqRepository;
import mss.mooncake.rfqnegotiation.repository.RfqTargetStoreRepository;
import mss.mooncake.rfqnegotiation.security.ActorContext;
import mss.mooncake.rfqnegotiation.service.RfqApplicationService;
import mss.mooncake.rfqnegotiation.util.Paging;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RfqApplicationServiceImpl implements RfqApplicationService {
    private static final Set<String> MUTABLE_FIELDS = Set.of("title", "description", "quantity", "budgetAmount",
            "requestedDeliveryDate", "deliveryLocation", "customizationRequirements", "expiresAt");
    private static final Set<String> SORT_FIELDS = Set.of("id", "createdAt", "title", "quantity",
            "budgetAmount", "requestedDeliveryDate", "expiresAt");
    private final RfqRepository rfqs;
    private final QuotationRepository quotations;
    private final RfqTargetStoreRepository targets;
    private final CatalogServiceClient catalog;
    private final ActorContext actor;
    private final Validator validator;
    private final RfqNotifier notifier;

    public RfqApplicationServiceImpl(RfqRepository rfqs, QuotationRepository quotations,
                                     RfqTargetStoreRepository targets, CatalogServiceClient catalog,
                                     ActorContext actor, Validator validator, RfqNotifier notifier) {
        this.rfqs = rfqs;
        this.quotations = quotations;
        this.targets = targets;
        this.catalog = catalog;
        this.actor = actor;
        this.validator = validator;
        this.notifier = notifier;
    }

    @Override
    @Transactional
    public RfqResponse create(CreateRfqRequest request) {
        validate(request);
        Long customerId = actor.customerId();
        // Invited stores are verified with Catalog before anything is written.
        List<StoreSummaryResponse> stores = request.targetStoreIds() == null ? List.of()
                : request.targetStoreIds().stream().distinct().map(this::activeStore).toList();
        RfqEntity rfq = new RfqEntity();
        rfq.setRfqCode("RFQ-" + UUID.randomUUID().toString().toUpperCase());
        rfq.setCustomerIdRef(customerId);
        rfq.setCustomerNameSnapshot(actor.fullName());
        apply(rfq, request);
        rfq.setAudience(stores.isEmpty() ? RfqAudience.ALL : RfqAudience.SELECTED_STORES);
        rfq.setStatus(RfqStatus.OPEN);
        rfq.setCreatedAt(now());
        RfqEntity saved = rfqs.save(rfq);
        List<RfqTargetStoreEntity> invited = stores.stream()
                .map(store -> new RfqTargetStoreEntity(saved.getId(), store.storeId(), store.supplierIdRef(), store.name()))
                .toList();
        if (invited.isEmpty()) {
            return response(saved, true, null);
        }
        targets.saveAll(invited);
        notifier.rfqInvited(saved, invited.stream().map(RfqTargetStoreEntity::getSupplierIdRef).distinct().toList());
        return response(saved, true, invited);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RfqResponse> list(String q, boolean mine, RfqStatus status, int page, int size, String sort) {
        Pageable pageable = Paging.page(page, size, sort, SORT_FIELDS, "createdAt");
        if (q != null && q.length() > 255) {
            throw ApiException.badRequest("INVALID_SEARCH", "q must contain at most 255 characters");
        }
        Long customerId = mine ? actor.customerId() : null;
        LocalDateTime now = now();
        // Public feed hides OPEN RFQs past expiresAt: they no longer take quotations. Detail stays readable.
        Specification<RfqEntity> listed = (root, query, cb) -> cb.or(cb.equal(root.get("status"), RfqStatus.CLOSED),
                cb.and(cb.equal(root.get("status"), RfqStatus.OPEN),
                        cb.or(cb.isNull(root.get("expiresAt")), cb.greaterThan(root.get("expiresAt"), now))));
        Specification<RfqEntity> filter = mine
                ? (root, query, cb) -> cb.equal(root.get("customerIdRef"), customerId)
                : listed.and(visibleInFeed(invitedSupplierId()));
        if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (q != null && !q.isBlank()) {
            String pattern = "%" + escapeLike(q.trim().toLowerCase(java.util.Locale.ROOT)) + "%";
            filter = filter.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("title")), pattern, '\\'),
                    cb.like(cb.lower(root.get("description")), pattern, '\\')));
        }
        return PageResponse.from(rfqs.findAll(filter, pageable).map(rfq -> response(rfq, mine)));
    }

    @Override
    @Transactional(readOnly = true)
    public RfqResponse get(Long rfqId) {
        RfqEntity rfq = rfqs.findById(rfqId).orElseThrow(this::notFound);
        boolean owner = rfq.getCustomerIdRef().equals(actor.optionalCustomerId());
        if (!owner && rfq.getStatus() != RfqStatus.OPEN && rfq.getStatus() != RfqStatus.CLOSED) {
            throw notFound();
        }
        if (rfq.getAudience() == RfqAudience.ALL) {
            return response(rfq, owner, null);
        }
        List<RfqTargetStoreEntity> invited = targets.findByRfqIdOrderByIdAsc(rfqId);
        if (owner) {
            return response(rfq, true, invited);
        }
        // An uninvited supplier gets the same 404 as a missing RFQ; an invited one sees only its own stores.
        Long supplierId = actor.optionalSupplierId();
        List<RfqTargetStoreEntity> own = invited.stream()
                .filter(target -> target.getSupplierIdRef().equals(supplierId)).toList();
        if (own.isEmpty()) {
            throw notFound();
        }
        return response(rfq, false, own);
    }

    @Override
    @Transactional
    public RfqResponse update(Long rfqId, Map<String, Object> patch) {
        RfqEntity rfq = ownedForUpdate(rfqId);
        if (rfq.getStatus() != RfqStatus.OPEN || (rfq.getExpiresAt() != null && !rfq.getExpiresAt().isAfter(now()))) {
            throw ApiException.conflict("RFQ_NOT_OPEN", "Only an open, unexpired RFQ can be edited");
        }
        if (patch == null || patch.isEmpty() || !MUTABLE_FIELDS.containsAll(patch.keySet())) {
            throw ApiException.badRequest("INVALID_PATCH", "Patch must contain only supported editable fields");
        }
        if (quotations.existsByRfqId(rfqId)) {
            throw ApiException.conflict("RFQ_HAS_QUOTATIONS", "RFQ content cannot change after a quotation is created");
        }
        CreateRfqRequest merged = new CreateRfqRequest(
                text(patch, "title", rfq.getTitle()),
                text(patch, "description", rfq.getDescription()),
                integer(patch, "quantity", rfq.getQuantity()),
                money(patch, "budgetAmount", rfq.getBudgetAmount()),
                date(patch, "requestedDeliveryDate", rfq.getRequestedDeliveryDate()),
                text(patch, "deliveryLocation", rfq.getDeliveryLocation()),
                text(patch, "customizationRequirements", rfq.getCustomizationRequirements()),
                timestamp(patch, "expiresAt", rfq.getExpiresAt()),
                null);
        validate(merged);
        apply(rfq, merged);
        return ownerResponse(rfq);
    }

    @Override
    @Transactional
    public RfqResponse close(Long rfqId) {
        RfqEntity rfq = ownedForUpdate(rfqId);
        if (rfq.getStatus() == RfqStatus.CLOSED) {
            // The buyer's own close wins: a cancelled order no longer reopens an RFQ the system had closed.
            rfq.setAutoClosed(false);
            return ownerResponse(rfq);
        }
        if (rfq.getStatus() != RfqStatus.OPEN) {
            throw ApiException.conflict("INVALID_RFQ_STATE", "Only an open RFQ can be closed");
        }
        rfq.setStatus(RfqStatus.CLOSED);
        return ownerResponse(rfq);
    }

    @Override
    @Transactional
    public RfqResponse cancel(Long rfqId) {
        RfqEntity rfq = ownedForUpdate(rfqId);
        if (rfq.getStatus() == RfqStatus.CANCELLED) {
            return ownerResponse(rfq);
        }
        // Orders made from this RFQ live on; the buyer can still close it to stop new quotations.
        if (quotations.hasLiveAcceptance(rfqId)) {
            throw ApiException.conflict("RFQ_HAS_ORDERS", "Yêu cầu đã có báo giá được chấp nhận hoặc đang tạo đơn; hãy đóng yêu cầu thay vì hủy");
        }
        rfq.setStatus(RfqStatus.CANCELLED);
        for (QuotationEntity waiting : quotations.findByRfqIdAndStatus(rfqId, QuotationStatus.SUBMITTED)) {
            waiting.setStatus(QuotationStatus.REJECTED);
            waiting.setDecisionReason("Người mua đã hủy yêu cầu báo giá");
            quotations.save(waiting);
            notifier.rfqCancelled(rfq, waiting);
        }
        return ownerResponse(rfq);
    }

    private RfqEntity ownedForUpdate(Long rfqId) {
        Long customerId = actor.customerId();
        RfqEntity rfq = rfqs.findByIdForUpdate(rfqId).orElseThrow(this::notFound);
        if (!Objects.equals(customerId, rfq.getCustomerIdRef())) {
            throw ApiException.forbidden("NOT_RESOURCE_OWNER", "You do not own this RFQ");
        }
        return rfq;
    }

    private void validate(CreateRfqRequest request) {
        if (request == null) {
            throw ApiException.badRequest("VALIDATION_FAILED", "Request body is required");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<CreateRfqRequest> violation : validator.validate(request)) {
            errors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage());
        }
        if (request.expiresAt() != null && request.requestedDeliveryDate() != null
                && !request.expiresAt().atZoneSameInstant(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate()
                        .isBefore(request.requestedDeliveryDate())) {
            errors.putIfAbsent("expiresAt", "Hạn nhận báo giá phải trước ngày cần giao");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid RFQ fields", errors);
        }
    }

    private void apply(RfqEntity rfq, CreateRfqRequest request) {
        rfq.setTitle(request.title().trim());
        rfq.setDescription(request.description());
        rfq.setQuantity(request.quantity());
        rfq.setBudgetAmount(request.budgetAmount() == null ? null
                : request.budgetAmount().setScale(2, RoundingMode.UNNECESSARY));
        rfq.setRequestedDeliveryDate(request.requestedDeliveryDate());
        rfq.setDeliveryLocation(request.deliveryLocation());
        rfq.setCustomizationRequirements(request.customizationRequirements());
        rfq.setExpiresAt(request.expiresAt() == null ? null
                : request.expiresAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS));
    }

    private StoreSummaryResponse activeStore(Long storeId) {
        StoreSummaryResponse store;
        try {
            store = catalog.getStoreSummary(storeId);
        } catch (FeignException.NotFound ex) {
            throw ApiException.notFound("STORE_NOT_FOUND", "Store " + storeId + " not found");
        } catch (FeignException ex) {
            throw ApiException.unavailable("CATALOG_UNAVAILABLE", "Cannot verify the selected stores");
        }
        if (store == null || !storeId.equals(store.storeId()) || store.supplierIdRef() == null) {
            throw ApiException.unavailable("CATALOG_INVALID_RESPONSE", "Invalid store data from Catalog");
        }
        if (!"ACTIVE".equals(store.status())) {
            throw ApiException.conflict("STORE_NOT_ACTIVE", "Store " + storeId + " is not active");
        }
        return store;
    }

    /** Caller's supplier for feed invitations. Any lookup failure only hides invitations; the public feed still works. */
    private Long invitedSupplierId() {
        try {
            return actor.optionalSupplierId();
        } catch (ApiException ex) {
            return null;
        }
    }

    /** Public RFQs, plus SELECTED_STORES RFQs that invite one of the caller's stores. */
    private Specification<RfqEntity> visibleInFeed(Long supplierId) {
        return (root, query, cb) -> {
            var isPublic = cb.equal(root.get("audience"), RfqAudience.ALL);
            if (supplierId == null) {
                return isPublic;
            }
            var invited = query.subquery(Long.class);
            var target = invited.from(RfqTargetStoreEntity.class);
            invited.select(target.get("id")).where(cb.equal(target.get("rfqId"), root.get("id")),
                    cb.equal(target.get("supplierIdRef"), supplierId));
            return cb.or(isPublic, cb.exists(invited));
        };
    }

    private RfqResponse ownerResponse(RfqEntity rfq) {
        return response(rfq, true, rfq.getAudience() == RfqAudience.ALL ? null : targets.findByRfqIdOrderByIdAsc(rfq.getId()));
    }

    private RfqResponse response(RfqEntity rfq, boolean owner) {
        return response(rfq, owner, null);
    }

    private RfqResponse response(RfqEntity rfq, boolean owner, List<RfqTargetStoreEntity> invited) {
        return new RfqResponse(rfq.getId().toString(), rfq.getRfqCode(),
                owner ? rfq.getCustomerIdRef().toString() : null, rfq.getTitle(), rfq.getDescription(),
                rfq.getQuantity(), rfq.getBudgetAmount() == null ? null : rfq.getBudgetAmount().toPlainString(),
                rfq.getRequestedDeliveryDate(), owner ? rfq.getDeliveryLocation() : null,
                owner ? rfq.getCustomizationRequirements() : null, rfq.getStatus(),
                utc(rfq.getExpiresAt()), utc(rfq.getCreatedAt()), rfq.getAudience(),
                invited == null ? null : invited.stream()
                        .map(target -> new RfqResponse.TargetStore(target.getStoreIdRef().toString(), target.getStoreNameSnapshot()))
                        .toList());
    }

    private String text(Map<String, Object> patch, String field, String previous) {
        if (!patch.containsKey(field)) {
            return previous;
        }
        Object value = patch.get(field);
        if (value != null && !(value instanceof String)) {
            throw invalidField(field, "must be a string or null");
        }
        return (String) value;
    }

    private Integer integer(Map<String, Object> patch, String field, Integer previous) {
        if (!patch.containsKey(field)) {
            return previous;
        }
        Object value = patch.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number) || value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            throw invalidField(field, "must be an integer");
        }
        try {
            return new BigDecimal(value.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw invalidField(field, "must fit a 32-bit integer");
        }
    }

    private BigDecimal money(Map<String, Object> patch, String field, BigDecimal previous) {
        if (!patch.containsKey(field)) {
            return previous;
        }
        String value = text(patch, field, null);
        if (value == null) {
            return null;
        }
        if (!value.matches("[0-9]+(?:\\.[0-9]{1,2})?")) {
            throw invalidField(field, "must be a non-negative decimal string with at most two fraction digits");
        }
        return new BigDecimal(value);
    }

    private LocalDate date(Map<String, Object> patch, String field, LocalDate previous) {
        if (!patch.containsKey(field)) {
            return previous;
        }
        String value = text(patch, field, null);
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw invalidField(field, "must use YYYY-MM-DD");
        }
    }

    private OffsetDateTime timestamp(Map<String, Object> patch, String field, LocalDateTime previous) {
        if (!patch.containsKey(field)) {
            return previous == null ? null : previous.atOffset(ZoneOffset.UTC);
        }
        String value = text(patch, field, null);
        try {
            return value == null ? null : OffsetDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw invalidField(field, "must be an ISO-8601 timestamp with a UTC offset");
        }
    }

    private ApiException invalidField(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid RFQ fields", Map.of(field, message));
    }

    private ApiException notFound() {
        return ApiException.notFound("RFQ_NOT_FOUND", "RFQ not found");
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    private String utc(LocalDateTime value) {
        return value == null ? null : DateTimeFormatter.ISO_INSTANT.format(value.toInstant(ZoneOffset.UTC));
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
