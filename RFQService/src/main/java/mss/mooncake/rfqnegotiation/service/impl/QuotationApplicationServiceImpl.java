package mss.mooncake.rfqnegotiation.service.impl;

import feign.FeignException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import mss.mooncake.rfqnegotiation.client.*;
import mss.mooncake.rfqnegotiation.client.dto.commerce.OrderResponse;
import mss.mooncake.rfqnegotiation.client.dto.catalog.StoreSummaryResponse;
import mss.mooncake.rfqnegotiation.client.dto.identity.SupplierSummaryResponse;
import mss.mooncake.rfqnegotiation.client.dto.identity.AddressSummaryResponse;
import mss.mooncake.rfqnegotiation.dto.request.*;
import mss.mooncake.rfqnegotiation.dto.response.QuotationAcceptedResponse;
import mss.mooncake.rfqnegotiation.entity.*;
import mss.mooncake.rfqnegotiation.enums.*;
import mss.mooncake.rfqnegotiation.exception.ApiException;
import mss.mooncake.rfqnegotiation.notification.RfqNotifier;
import mss.mooncake.rfqnegotiation.repository.*;
import mss.mooncake.rfqnegotiation.security.ActorContext;
import mss.mooncake.rfqnegotiation.service.QuotationApplicationService;
import mss.mooncake.rfqnegotiation.util.DeliveryDateRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class QuotationApplicationServiceImpl implements QuotationApplicationService {
    private static final Logger log = LoggerFactory.getLogger(QuotationApplicationServiceImpl.class);
    private static final String DECLINED_AWARDED = "Không được chọn: người mua đã chốt báo giá khác";
    /** Reason stored before single-award; such declines are still restored when the RFQ reopens. */
    private static final String DECLINED_FULL_LEGACY = "Không được chọn: yêu cầu báo giá đã đủ số lượng";
    private final RfqRepository rfqs;
    private final QuotationRepository quotations;
    private final QuotationItemRepository items;
    private final QuotationConversionRepository conversions;
    private final ActorContext actor;
    private final IdentityServiceClient identity;
    private final CatalogServiceClient catalog;
    private final CommerceServiceClient commerce;
    private final ObjectMapper json;
    private final Validator validator;
    private final TransactionTemplate tx;
    private final RfqNotifier notifier;

    public QuotationApplicationServiceImpl(RfqRepository rfqs, QuotationRepository quotations,
            QuotationItemRepository items, QuotationConversionRepository conversions, ActorContext actor,
            IdentityServiceClient identity, CatalogServiceClient catalog, CommerceServiceClient commerce,
            ObjectMapper json, Validator validator, PlatformTransactionManager transactionManager, RfqNotifier notifier) {
        this.rfqs=rfqs; this.quotations=quotations; this.items=items; this.conversions=conversions;
        this.actor=actor; this.identity=identity; this.catalog=catalog; this.commerce=commerce;
        this.json=json; this.validator=validator; this.tx=new TransactionTemplate(transactionManager);
        this.notifier=notifier;
    }

    @Override public QuotationAcceptedResponse acceptQuotation(Long id, AcceptQuotationRequest request, String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,100}")) throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key phải dài 1..100 và chỉ gồm chữ, số, gạch ngang/gạch dưới");
        Long customerId=actor.customerId();
        QuotationEntity initial=quotation(id);
        String hash=hash(request.expectedVersion()+":"+request.deliveryAddressId());
        RfqEntity initialRfq=rfqs.findById(initial.getRfqId()).orElseThrow(() -> ApiException.notFound("RFQ_NOT_FOUND", "Không tìm thấy RFQ"));
        if (!Objects.equals(customerId,initialRfq.getCustomerIdRef())) throw ApiException.forbidden("NOT_RFQ_OWNER", "Chỉ chủ RFQ được chấp nhận báo giá");
        Optional<QuotationConversionEntity> existing=conversions.findByQuotationId(id);
        if (existing.isPresent()) {
            assertSameIntent(existing.get(),key,hash);
            return accepted(resolve(existing.get(),true));
        }
        // Resolve remote summaries before acquiring local locks; state is validated again under lock.
        SnapshotData snapshotData=loadSnapshotData(initial,customerId,request.deliveryAddressId());
        QuotationConversionEntity intent=tx.execute(s -> {
            RfqEntity rfq=rfqs.findByIdForUpdate(initial.getRfqId()).orElseThrow(() -> ApiException.notFound("RFQ_NOT_FOUND", "Không tìm thấy RFQ"));
            if (!Objects.equals(customerId,rfq.getCustomerIdRef())) throw ApiException.forbidden("NOT_RFQ_OWNER", "Chỉ chủ RFQ được chấp nhận báo giá");
            QuotationEntity q=quotations.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("QUOTATION_NOT_FOUND", "Không tìm thấy báo giá"));
            Optional<QuotationConversionEntity> previous=conversions.findByQuotationId(id);
            if (previous.isPresent()) {
                QuotationConversionEntity saved=previous.get();
                assertSameIntent(saved,key,hash);
                return saved;
            }
            if (conversions.findByCustomerIdRefAndIdempotencyKey(customerId,key).isPresent()) throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Khóa đã được dùng cho báo giá khác");
            LocalDateTime now=now();
            if (!Objects.equals(q.getVersionNo(),request.expectedVersion())) throw ApiException.conflict("QUOTATION_VERSION_CONFLICT", "Phiên bản báo giá không khớp");
            if (rfq.getStatus()!=RfqStatus.OPEN && rfq.getStatus()!=RfqStatus.CLOSED) throw ApiException.conflict("RFQ_NOT_ACCEPTABLE", "RFQ đã hủy hoặc chưa được công bố");
            // Single award: one pending or completed acceptance (order not cancelled) ends the competition.
            if (conversions.committedQuantity(rfq.getId())>0) throw ApiException.conflict("RFQ_ALREADY_AWARDED", "Yêu cầu báo giá đã chốt với một nhà cung cấp; không thể chấp nhận thêm báo giá");
            if (q.getStatus()!=QuotationStatus.SUBMITTED || !q.getValidUntil().isAfter(now)) throw ApiException.conflict("QUOTATION_NOT_ACCEPTABLE", "Báo giá không còn được chấp nhận");
            // The one awarded quotation never covers more units than the RFQ asked for.
            long wanted=items.quantityOf(q.getId());
            if (wanted>rfq.getQuantity()) throw ApiException.conflict("RFQ_QUANTITY_EXCEEDED",
                    "Báo giá có "+wanted+" sản phẩm, vượt số lượng của yêu cầu báo giá ("+rfq.getQuantity()+").");
            CreateOrderFromAcceptedQuotationRequest snapshot=makeSnapshot(rfq,q,snapshotData,"quotation-v1-"+id,now);
            if (!validator.validate(snapshot).isEmpty()) throw ApiException.unavailable("INVALID_ACCEPTANCE_SNAPSHOT", "Dữ liệu báo giá hoặc địa chỉ chưa đáp ứng hợp đồng tạo đơn");
            QuotationConversionEntity saved=new QuotationConversionEntity(); saved.setQuotationId(id); saved.setCustomerIdRef(customerId);
            saved.setIdempotencyKey(key); saved.setRequestHash(hash); saved.setSnapshotJson(json.writeValueAsString(snapshot));
            saved.setStatus("PENDING"); saved.setAcceptedAt(now); saved.setUpdatedAt(now);
            return conversions.saveAndFlush(saved);
        });
        return accepted(resolve(intent, false));
    }

    private QuotationAcceptedResponse accepted(QuotationConversionEntity c) {
        if ("FAILED".equals(c.getStatus())) throw ApiException.conflict("QUOTATION_CONVERSION_FAILED", "Hệ thống đơn hàng đã từ chối tạo đơn cho báo giá này; nhà cung cấp cần gửi phiên bản mới");
        return response(c);
    }

    private SnapshotData loadSnapshotData(QuotationEntity q, Long customerId, Long addressId) {
        try {
            AddressSummaryResponse address;
            try { address=identity.getAddressSummary(addressId); }
            catch (FeignException.NotFound missing) { throw ApiException.notFound("ADDRESS_NOT_FOUND", "Không tìm thấy địa chỉ giao hàng"); }
            if (address==null || !Objects.equals(address.addressId(),addressId) || !Objects.equals(address.customerId(),customerId)) throw ApiException.forbidden("ADDRESS_NOT_OWNED", "Địa chỉ không thuộc người mua");
            StoreSummaryResponse store=catalog.getStoreSummary(q.getStoreIdRef());
            SupplierSummaryResponse supplier=identity.getSupplierSummary(q.getSupplierIdRef());
            if (store==null || supplier==null || !Objects.equals(store.storeId(),q.getStoreIdRef()) || !Objects.equals(store.supplierIdRef(),q.getSupplierIdRef()) || !Objects.equals(supplier.supplierId(),q.getSupplierIdRef()) || !"ACTIVE".equals(store.status())) throw ApiException.conflict("STORE_NOT_AVAILABLE", "Cửa hàng không còn hợp lệ");
            if (Objects.equals(supplier.userId(),actor.userId())) throw ApiException.forbidden("SELF_DEALING", "Không thể chấp nhận báo giá của chính tài khoản nhà cung cấp của bạn");
            if (!"VERIFIED".equals(supplier.verificationStatus())) throw ApiException.conflict("SUPPLIER_NOT_VERIFIED", "Nhà cung cấp đang bị khóa hoặc chưa được xác minh nên không thể chốt báo giá này");
            return new SnapshotData(store,supplier,address);
        } catch (FeignException ex) { throw ApiException.unavailable("SNAPSHOT_UNAVAILABLE", "Không lấy được dữ liệu địa chỉ/cửa hàng; hãy thử lại"); }
    }

    private CreateOrderFromAcceptedQuotationRequest makeSnapshot(RfqEntity rfq, QuotationEntity q, SnapshotData data, String key, LocalDateTime now) {
            AddressSummaryResponse address=data.address(); StoreSummaryResponse store=data.store(); SupplierSummaryResponse supplier=data.supplier();
            if (q.getProposedDeliveryDate()==null || q.getProposedDeliveryDate().isBefore(now.toLocalDate())) throw ApiException.conflict("INVALID_DELIVERY_DATE", "Ngày giao hàng không hợp lệ");
            if (DeliveryDateRule.tooSoon(q.getDepositPercent(), q.getProposedDeliveryDate(), now)) throw ApiException.conflict(DeliveryDateRule.errorCode(q.getDepositPercent()),
                    "Ngày giao " + DeliveryDateRule.format(q.getProposedDeliveryDate()) + " quá gần. " + DeliveryDateRule.requirement(q.getDepositPercent(), now)
                    + " Hãy nhắn nhà cung cấp gửi phiên bản mới.");
            List<CreateOrderFromAcceptedQuotationRequest.Item> lines=items.findByQuotationIdOrderByIdAsc(q.getId()).stream().map(i -> new CreateOrderFromAcceptedQuotationRequest.Item(
                    i.getVariantIdRef(),null,i.getItemNameSnapshot(),null,i.getSkuSnapshot(),i.orderImageUrl(),
                    i.getQuantity(),i.getUnitPrice(),i.getDiscountAmount(),i.getSubtotal())).toList();
            if (lines.isEmpty()) throw ApiException.conflict("EMPTY_QUOTATION", "Báo giá không có sản phẩm");
            BigDecimal subtotal=lines.stream().map(CreateOrderFromAcceptedQuotationRequest.Item::subtotal).reduce(BigDecimal.ZERO,BigDecimal::add);
            if (subtotal.add(q.getShippingFee()).compareTo(q.getTotalAmount())!=0) throw ApiException.conflict("QUOTATION_TOTAL_INVALID", "Tổng báo giá không khớp");
            if (q.getTotalAmount().signum()<=0) throw ApiException.conflict("ZERO_TOTAL_NOT_SUPPORTED", "Đơn báo giá phải có tổng tiền lớn hơn 0");
            return new CreateOrderFromAcceptedQuotationRequest(rfq.getId(),q.getId(),q.getVersionNo(),now,
                    rfq.getCustomerIdRef(),q.getSupplierIdRef(),q.getStoreIdRef(),key,
                    new CreateOrderFromAcceptedQuotationRequest.StoreSnapshot(store.name(),supplier.companyName()),
                    new CreateOrderFromAcceptedQuotationRequest.AddressSnapshot(address.addressId(),address.recipientName(),address.recipientPhone(),address.addressLine(),address.ward(),address.district(),address.city()),
                    new CreateOrderFromAcceptedQuotationRequest.Terms(q.getDepositPercent(),q.getProposedDeliveryDate(),q.getShippingMethod(),q.getShippingFee(),q.getPaymentTerms()),lines,subtotal,q.getTotalAmount());
    }

    /** Network calls run outside local transactions. Every retry reuses the persisted snapshot/key. */
    private QuotationConversionEntity resolve(QuotationConversionEntity intent, boolean lookupFirst) {
        if (!"PENDING".equals(intent.getStatus())) return intent;
        try {
            OrderResponse order=null;
            if (lookupFirst) {
                try { order=commerce.getOrderByQuotation(intent.getQuotationId()); }
                catch (FeignException.NotFound notYetCommitted) { /* Safe POST retries retain the same durable key. */ }
            }
            if (order==null) {
                try { order=commerce.createOrderFromAcceptedQuotation(json.readValue(intent.getSnapshotJson(),CreateOrderFromAcceptedQuotationRequest.class)); }
                catch (FeignException refused) {
                    if (refused.status()!=400 && refused.status()!=409 && refused.status()!=422) throw refused;
                    // A refusal can follow a commit whose response was lost; only a confirmed missing order is final.
                    try { order=commerce.getOrderByQuotation(intent.getQuotationId()); }
                    catch (FeignException.NotFound missing) { return fail(intent,refused.status()); }
                }
            }
            CreateOrderFromAcceptedQuotationRequest snapshot=json.readValue(intent.getSnapshotJson(),CreateOrderFromAcceptedQuotationRequest.class);
            if (order==null || order.orderId()==null || order.sellerOrderId()==null || !Objects.equals(order.quotationId(),intent.getQuotationId()) || !Objects.equals(order.quotationVersion(),snapshot.quotationVersion())) throw new IllegalStateException("Commerce response does not match intent");
            final OrderResponse committed=order;
            return tx.execute(s -> {
                QuotationEntity q0=quotation(intent.getQuotationId());
                RfqEntity rfq=rfqs.findByIdForUpdate(q0.getRfqId()).orElseThrow();
                QuotationEntity q=quotations.findByIdForUpdate(q0.getId()).orElseThrow();
                QuotationConversionEntity saved=conversions.findByQuotationId(q.getId()).orElseThrow();
                if (!"COMPLETED".equals(saved.getStatus())) {
                    saved.setOrderIdRef(committed.orderId()); saved.setSellerOrderIdRef(committed.sellerOrderId());
                    saved.setStatus("COMPLETED"); saved.setUpdatedAt(now());
                    q.setStatus(QuotationStatus.ACCEPTED); q.setAcceptedAt(saved.getAcceptedAt());
                    quotations.save(q); conversions.save(saved);
                    closeOnAward(rfq);
                    notifier.quotationAccepted(rfq,q,saved);
                }
                return saved;
            });
        } catch (RuntimeException unknownOutcome) {
            log.warn("Quotation conversion {} needs reconciliation; failureType={} downstreamStatus={}",
                    intent.getQuotationId(),unknownOutcome.getClass().getSimpleName(),
                    unknownOutcome instanceof FeignException failure ? failure.status() : null);
            return tx.execute(s -> {
                QuotationEntity q=quotation(intent.getQuotationId());
                rfqs.findByIdForUpdate(q.getRfqId()).orElseThrow();
                quotations.findByIdForUpdate(q.getId()).orElseThrow();
                QuotationConversionEntity saved=conversions.findByQuotationId(intent.getQuotationId()).orElseThrow();
                if (!"COMPLETED".equals(saved.getStatus())) { saved.setUpdatedAt(now()); conversions.save(saved); }
                return saved;
            });
        }
    }

    private QuotationConversionEntity fail(QuotationConversionEntity intent, int downstreamStatus) {
        log.warn("Quotation conversion {} refused by Commerce; downstreamStatus={}", intent.getQuotationId(), downstreamStatus);
        return tx.execute(s -> {
            QuotationEntity q=quotation(intent.getQuotationId());
            rfqs.findByIdForUpdate(q.getRfqId()).orElseThrow();
            quotations.findByIdForUpdate(q.getId()).orElseThrow();
            QuotationConversionEntity saved=conversions.findByQuotationId(intent.getQuotationId()).orElseThrow();
            if ("PENDING".equals(saved.getStatus())) { saved.setStatus("FAILED"); saved.setUpdatedAt(now()); conversions.save(saved); }
            return saved;
        });
    }

    @Override public QuotationAcceptedResponse getOrderLink(Long id) {
        QuotationEntity q=quotation(id); RfqEntity rfq=rfqs.findById(q.getRfqId()).orElseThrow();
        Long customerId=actor.optionalCustomerId();
        if (actor.optionalSupplierId()!=null || !Objects.equals(customerId,rfq.getCustomerIdRef())) actor.assertSupplierOwner(q.getSupplierIdRef());
        return response(conversions.findByQuotationId(id).orElseThrow(() -> ApiException.notFound("CONVERSION_NOT_FOUND", "Chưa có yêu cầu tạo đơn cho báo giá")));
    }
    @Override public void recoverPendingConversions() {
        for (QuotationConversionEntity intent:conversions.findByStatusOrderByUpdatedAtAsc("PENDING",PageRequest.of(0,50))) resolve(intent,true);
    }
    @Override public void syncAcceptedOrders() {
        for (QuotationConversionEntity conversion:conversions.findOrdersToSync(PageRequest.of(0,50))) {
            OrderResponse order;
            try { order=commerce.getOrderByQuotation(conversion.getQuotationId()); }
            catch (RuntimeException unavailable) {
                log.warn("Order status of quotation {} unavailable; failureType={}", conversion.getQuotationId(), unavailable.getClass().getSimpleName());
                continue;
            }
            if (order==null || order.status()==null) continue;
            tx.executeWithoutResult(s -> applyOrderStatus(conversion.getQuotationId(), order.status()));
        }
    }

    /** A cancelled order frees its quantity; an RFQ the system closed for being full reopens. */
    private void applyOrderStatus(Long quotationId, String orderStatus) {
        RfqEntity rfq=rfqs.findByIdForUpdate(quotation(quotationId).getRfqId()).orElseThrow();
        QuotationEntity q=quotations.findByIdForUpdate(quotationId).orElseThrow();
        QuotationConversionEntity saved=conversions.findByQuotationId(quotationId).orElseThrow();
        saved.setOrderStatus(orderStatus); saved.setOrderCheckedAt(now()); conversions.save(saved);
        if (!"CANCELLED".equals(orderStatus) || q.getStatus()!=QuotationStatus.ACCEPTED) return;
        q.setStatus(QuotationStatus.ORDER_CANCELLED); quotations.save(q);
        notifier.orderCancelled(rfq,q,saved);
        if (rfq.isAutoClosed() && rfq.getStatus()==RfqStatus.CLOSED && conversions.committedQuantity(rfq.getId())==0) {
            rfq.setStatus(RfqStatus.OPEN); rfq.setAutoClosed(false); rfqs.save(rfq);
            // Quotations declined only because another one was chosen compete again while still valid.
            for (QuotationEntity declined:quotations.findByRfqIdAndStatus(rfq.getId(),QuotationStatus.REJECTED)) {
                boolean declinedByAward=DECLINED_AWARDED.equals(declined.getDecisionReason()) || DECLINED_FULL_LEGACY.equals(declined.getDecisionReason());
                if (!declinedByAward || !declined.getValidUntil().isAfter(now())) continue;
                declined.setStatus(QuotationStatus.SUBMITTED); declined.setDecisionReason(null); quotations.save(declined);
            }
        }
    }

    /** Runs under the RFQ lock once an acceptance completes: the first award closes the RFQ and declines the rest. */
    private void closeOnAward(RfqEntity rfq) {
        if (rfq.getStatus()==RfqStatus.OPEN) { rfq.setStatus(RfqStatus.CLOSED); rfq.setAutoClosed(true); rfqs.save(rfq); }
        for (QuotationEntity other:quotations.findByRfqIdAndStatus(rfq.getId(),QuotationStatus.SUBMITTED)) {
            if (conversions.existsByQuotationIdAndStatusNot(other.getId(),"FAILED")) continue;
            other.setStatus(QuotationStatus.REJECTED);
            other.setDecisionReason(DECLINED_AWARDED);
            quotations.save(other);
            notifier.quotationNotSelected(rfq,other);
        }
    }
    private QuotationEntity quotation(Long id) { return quotations.findById(id).orElseThrow(() -> ApiException.notFound("QUOTATION_NOT_FOUND", "Không tìm thấy báo giá")); }
    private void assertSameIntent(QuotationConversionEntity saved,String key,String hash) {
        if (!saved.getIdempotencyKey().equals(key) || !saved.getRequestHash().equals(hash)) throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Báo giá đã có yêu cầu chấp nhận khác");
    }
    private record SnapshotData(StoreSummaryResponse store,SupplierSummaryResponse supplier,AddressSummaryResponse address) { }
    private QuotationAcceptedResponse response(QuotationConversionEntity c) {
        QuotationEntity q=quotation(c.getQuotationId());
        return new QuotationAcceptedResponse(q.getId().toString(),q.getRfqId().toString(),c.getCustomerIdRef().toString(),q.getSupplierIdRef().toString(),q.getStoreIdRef().toString(),q.getVersionNo(),
                c.getOrderIdRef()==null?null:c.getOrderIdRef().toString(),c.getSellerOrderIdRef()==null?null:c.getSellerOrderIdRef().toString(),c.getStatus(),c.getOrderStatus());
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
