package mss.mooncake.rfqnegotiation.service.impl;

import feign.FeignException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
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
import mss.mooncake.rfqnegotiation.repository.*;
import mss.mooncake.rfqnegotiation.security.ActorContext;
import mss.mooncake.rfqnegotiation.service.QuotationApplicationService;
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

    public QuotationApplicationServiceImpl(RfqRepository rfqs, QuotationRepository quotations,
            QuotationItemRepository items, QuotationConversionRepository conversions, ActorContext actor,
            IdentityServiceClient identity, CatalogServiceClient catalog, CommerceServiceClient commerce,
            ObjectMapper json, Validator validator, PlatformTransactionManager transactionManager) {
        this.rfqs=rfqs; this.quotations=quotations; this.items=items; this.conversions=conversions;
        this.actor=actor; this.identity=identity; this.catalog=catalog; this.commerce=commerce;
        this.json=json; this.validator=validator; this.tx=new TransactionTemplate(transactionManager);
    }

    @Override public QuotationAcceptedResponse acceptQuotation(Long id, AcceptQuotationRequest request, String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,100}")) throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key phải dài 1..100 và chỉ gồm chữ, số, gạch ngang/gạch dưới");
        Long customerId=actor.customerId();
        var initial=quotation(id);
        String hash=hash(request.expectedVersion()+":"+request.deliveryAddressId());
        var initialRfq=rfqs.findById(initial.getRfqId()).orElseThrow(() -> ApiException.notFound("RFQ_NOT_FOUND", "Không tìm thấy RFQ"));
        if (!Objects.equals(customerId,initialRfq.getCustomerIdRef())) throw ApiException.forbidden("NOT_RFQ_OWNER", "Chỉ chủ RFQ được chấp nhận báo giá");
        var existing=conversions.findByQuotationId(id);
        if (existing.isPresent()) {
            assertSameIntent(existing.get(),key,hash);
            return accepted(resolve(existing.get(),true));
        }
        // Resolve remote summaries before acquiring local locks; state is validated again under lock.
        var snapshotData=loadSnapshotData(initial,customerId,request.deliveryAddressId());
        var intent=tx.execute(s -> {
            var rfq=rfqs.findByIdForUpdate(initial.getRfqId()).orElseThrow(() -> ApiException.notFound("RFQ_NOT_FOUND", "Không tìm thấy RFQ"));
            if (!Objects.equals(customerId,rfq.getCustomerIdRef())) throw ApiException.forbidden("NOT_RFQ_OWNER", "Chỉ chủ RFQ được chấp nhận báo giá");
            var q=quotations.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("QUOTATION_NOT_FOUND", "Không tìm thấy báo giá"));
            var previous=conversions.findByQuotationId(id);
            if (previous.isPresent()) {
                var saved=previous.get();
                assertSameIntent(saved,key,hash);
                return saved;
            }
            if (conversions.findByCustomerIdRefAndIdempotencyKey(customerId,key).isPresent()) throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Khóa đã được dùng cho báo giá khác");
            LocalDateTime now=now();
            if (!Objects.equals(q.getVersionNo(),request.expectedVersion())) throw ApiException.conflict("QUOTATION_VERSION_CONFLICT", "Phiên bản báo giá không khớp");
            if (rfq.getStatus()!=RfqStatus.OPEN && rfq.getStatus()!=RfqStatus.CLOSED) throw ApiException.conflict("RFQ_NOT_ACCEPTABLE", "RFQ đã hủy hoặc chưa được công bố");
            if (q.getStatus()!=QuotationStatus.SUBMITTED || !q.getValidUntil().isAfter(now)) throw ApiException.conflict("QUOTATION_NOT_ACCEPTABLE", "Báo giá không còn được chấp nhận");
            var snapshot=makeSnapshot(rfq,q,snapshotData,"quotation-v1-"+id,now);
            if (!validator.validate(snapshot).isEmpty()) throw ApiException.unavailable("INVALID_ACCEPTANCE_SNAPSHOT", "Dữ liệu báo giá hoặc địa chỉ chưa đáp ứng hợp đồng tạo đơn");
            var saved=new QuotationConversionEntity(); saved.setQuotationId(id); saved.setCustomerIdRef(customerId);
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
            var address=identity.getAddressSummary(addressId);
            if (address==null || !Objects.equals(address.addressId(),addressId) || !Objects.equals(address.customerId(),customerId)) throw ApiException.forbidden("ADDRESS_NOT_OWNED", "Địa chỉ không thuộc người mua");
            var store=catalog.getStoreSummary(q.getStoreIdRef());
            var supplier=identity.getSupplierSummary(q.getSupplierIdRef());
            if (store==null || supplier==null || !Objects.equals(store.storeId(),q.getStoreIdRef()) || !Objects.equals(store.supplierIdRef(),q.getSupplierIdRef()) || !Objects.equals(supplier.supplierId(),q.getSupplierIdRef()) || !"ACTIVE".equals(store.status())) throw ApiException.conflict("STORE_NOT_AVAILABLE", "Cửa hàng không còn hợp lệ");
            return new SnapshotData(store,supplier,address);
        } catch (FeignException ex) { throw ApiException.unavailable("SNAPSHOT_UNAVAILABLE", "Không lấy được dữ liệu địa chỉ/cửa hàng; hãy thử lại"); }
    }

    private CreateOrderFromAcceptedQuotationRequest makeSnapshot(RfqEntity rfq, QuotationEntity q, SnapshotData data, String key, LocalDateTime now) {
            var address=data.address(); var store=data.store(); var supplier=data.supplier();
            if (q.getProposedDeliveryDate()==null || q.getProposedDeliveryDate().isBefore(now.toLocalDate())) throw ApiException.conflict("INVALID_DELIVERY_DATE", "Ngày giao hàng không hợp lệ");
            var lines=items.findByQuotationIdOrderByIdAsc(q.getId()).stream().map(i -> new CreateOrderFromAcceptedQuotationRequest.Item(
                    i.getVariantIdRef(),null,i.getItemNameSnapshot(),null,i.getSkuSnapshot(),null,
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
            var snapshot=json.readValue(intent.getSnapshotJson(),CreateOrderFromAcceptedQuotationRequest.class);
            if (order==null || order.orderId()==null || order.sellerOrderId()==null || !Objects.equals(order.quotationId(),intent.getQuotationId()) || !Objects.equals(order.quotationVersion(),snapshot.quotationVersion())) throw new IllegalStateException("Commerce response does not match intent");
            final OrderResponse committed=order;
            return tx.execute(s -> {
                var q0=quotation(intent.getQuotationId());
                rfqs.findByIdForUpdate(q0.getRfqId()).orElseThrow();
                var q=quotations.findByIdForUpdate(q0.getId()).orElseThrow();
                var saved=conversions.findByQuotationId(q.getId()).orElseThrow();
                if (!"COMPLETED".equals(saved.getStatus())) {
                    saved.setOrderIdRef(committed.orderId()); saved.setSellerOrderIdRef(committed.sellerOrderId());
                    saved.setStatus("COMPLETED"); saved.setUpdatedAt(now());
                    q.setStatus(QuotationStatus.ACCEPTED); q.setAcceptedAt(saved.getAcceptedAt());
                    quotations.save(q); conversions.save(saved);
                }
                return saved;
            });
        } catch (RuntimeException unknownOutcome) {
            log.warn("Quotation conversion {} needs reconciliation; failureType={} downstreamStatus={}",
                    intent.getQuotationId(),unknownOutcome.getClass().getSimpleName(),
                    unknownOutcome instanceof FeignException failure ? failure.status() : null);
            return tx.execute(s -> {
                var q=quotation(intent.getQuotationId());
                rfqs.findByIdForUpdate(q.getRfqId()).orElseThrow();
                quotations.findByIdForUpdate(q.getId()).orElseThrow();
                var saved=conversions.findByQuotationId(intent.getQuotationId()).orElseThrow();
                if (!"COMPLETED".equals(saved.getStatus())) { saved.setUpdatedAt(now()); conversions.save(saved); }
                return saved;
            });
        }
    }

    private QuotationConversionEntity fail(QuotationConversionEntity intent, int downstreamStatus) {
        log.warn("Quotation conversion {} refused by Commerce; downstreamStatus={}", intent.getQuotationId(), downstreamStatus);
        return tx.execute(s -> {
            var q=quotation(intent.getQuotationId());
            rfqs.findByIdForUpdate(q.getRfqId()).orElseThrow();
            quotations.findByIdForUpdate(q.getId()).orElseThrow();
            var saved=conversions.findByQuotationId(intent.getQuotationId()).orElseThrow();
            if ("PENDING".equals(saved.getStatus())) { saved.setStatus("FAILED"); saved.setUpdatedAt(now()); conversions.save(saved); }
            return saved;
        });
    }

    @Override public QuotationAcceptedResponse getOrderLink(Long id) {
        var q=quotation(id); var rfq=rfqs.findById(q.getRfqId()).orElseThrow();
        Long customerId=actor.optionalCustomerId();
        if (!Objects.equals(customerId,rfq.getCustomerIdRef())) actor.assertSupplierOwner(q.getSupplierIdRef());
        return response(conversions.findByQuotationId(id).orElseThrow(() -> ApiException.notFound("CONVERSION_NOT_FOUND", "Chưa có yêu cầu tạo đơn cho báo giá")));
    }
    @Override public void recoverPendingConversions() {
        for (var intent:conversions.findByStatusOrderByUpdatedAtAsc("PENDING",PageRequest.of(0,50))) resolve(intent,true);
    }
    private QuotationEntity quotation(Long id) { return quotations.findById(id).orElseThrow(() -> ApiException.notFound("QUOTATION_NOT_FOUND", "Không tìm thấy báo giá")); }
    private void assertSameIntent(QuotationConversionEntity saved,String key,String hash) {
        if (!saved.getIdempotencyKey().equals(key) || !saved.getRequestHash().equals(hash)) throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Báo giá đã có yêu cầu chấp nhận khác");
    }
    private record SnapshotData(StoreSummaryResponse store,SupplierSummaryResponse supplier,AddressSummaryResponse address) { }
    private QuotationAcceptedResponse response(QuotationConversionEntity c) {
        var q=quotation(c.getQuotationId());
        return new QuotationAcceptedResponse(q.getId().toString(),q.getRfqId().toString(),c.getCustomerIdRef().toString(),q.getSupplierIdRef().toString(),q.getStoreIdRef().toString(),q.getVersionNo(),
                c.getOrderIdRef()==null?null:c.getOrderIdRef().toString(),c.getSellerOrderIdRef()==null?null:c.getSellerOrderIdRef().toString(),c.getStatus());
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
