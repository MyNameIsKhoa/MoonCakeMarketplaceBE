package mss.mooncake.commerce.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import mss.mooncake.commerce.dto.request.CreateOrderFromQuotationRequest;
import mss.mooncake.commerce.dto.response.OrderResponse;
import mss.mooncake.commerce.dto.internal.SellerOrderPaymentContext;
import mss.mooncake.commerce.entity.*;
import mss.mooncake.commerce.enums.*;
import mss.mooncake.commerce.repository.*;
import mss.mooncake.commerce.service.OrderApplicationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrderApplicationServiceImpl implements OrderApplicationService {
    private final QuotationOrderConversionRepository conversions;
    private final CustomerOrderRepository orders;
    private final SellerOrderRepository sellers;
    private final DeliveryAddressRepository addresses;
    private final OrderItemRepository items;
    private final PaymentRepository payments;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public OrderApplicationServiceImpl(QuotationOrderConversionRepository conversions, CustomerOrderRepository orders,
            SellerOrderRepository sellers, DeliveryAddressRepository addresses, OrderItemRepository items,
            PaymentRepository payments, PlatformTransactionManager manager, ObjectMapper json) {
        this.conversions=conversions; this.orders=orders; this.sellers=sellers; this.addresses=addresses;
        this.items=items; this.payments=payments; this.tx=new TransactionTemplate(manager); this.json=json;
    }
    @Override public OrderResponse createOrderFromAcceptedQuotation(CreateOrderFromQuotationRequest request) {
        validateAmounts(request);
        String hash=hash(json.writeValueAsString(request));
        try { return tx.execute(s -> {
            var replay=conversions.findByQuotationIdRef(request.quotationId());
            if (replay.isPresent()) return replay(replay.get(),request,hash);
            if (conversions.findByIdempotencyKey(request.idempotencyKey()).isPresent()) throw conflict();
            // Unique insert claims this quotation. Its claim and all snapshots commit atomically.
            var claim=new QuotationOrderConversionEntity(); claim.setQuotationIdRef(request.quotationId());
            claim.setQuotationVersion(request.quotationVersion()); claim.setIdempotencyKey(request.idempotencyKey()); claim.setRequestHash(hash);
            conversions.saveAndFlush(claim);
            var order=new CustomerOrderEntity(); order.setOrderNo("RFQ-"+UUID.randomUUID()); order.setCustomerIdRef(request.customerId());
            order.setSourceType("RFQ"); order.setRfqIdRef(request.rfqId()); order.setSubtotal(request.subtotal());
            order.setDiscountTotal(BigDecimal.ZERO); order.setShippingTotal(request.terms().shippingFee());
            order.setGrandTotal(request.totalAmount()); order.setStatus("AWAITING_DEPOSIT"); order.setPlacedAt(request.acceptedAt()); orders.saveAndFlush(order);
            var address=new DeliveryAddressEntity(); var a=request.deliveryAddressSnapshot();
            address.setOrderId(order.getId()); address.setSourceAddressIdRef(a.sourceAddressId()); address.setRecipientName(a.recipientName());
            address.setRecipientPhone(a.recipientPhone()); address.setAddressLine(a.addressLine()); address.setWard(a.ward());
            address.setDistrict(a.district()); address.setCity(a.city()); addresses.save(address);
            BigDecimal deposit=request.totalAmount().multiply(request.terms().depositPercent()).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP);
            BigDecimal remaining=request.totalAmount().subtract(deposit);
            var seller=new SellerOrderEntity(); seller.setOrderId(order.getId()); seller.setSellerOrderNo("RFQS-"+UUID.randomUUID());
            seller.setStoreIdRef(request.storeId()); seller.setSupplierIdRef(request.supplierId()); seller.setQuotationIdRef(request.quotationId());
            seller.setStoreNameSnapshot(request.storeSnapshot().storeName()); seller.setSupplierNameSnapshot(request.storeSnapshot().supplierName());
            seller.setSubtotal(request.subtotal()); seller.setShippingFee(request.terms().shippingFee()); seller.setTotalAmount(request.totalAmount());
            seller.setDepositPercent(request.terms().depositPercent()); seller.setDepositAmount(deposit); seller.setRemainingAmount(remaining);
            seller.setDeliveryDateSnapshot(request.terms().deliveryDate()); seller.setRemainingDueAt(request.terms().deliveryDate().minusDays(3).atStartOfDay());
            seller.setShippingMethodSnapshot(request.terms().shippingMethod()); seller.setPaymentTermsSnapshot(request.terms().paymentTerms());
            seller.setStatus(deposit.signum()>0?"AWAITING_DEPOSIT":"AWAITING_PAYMENT"); order.setStatus(seller.getStatus()); sellers.saveAndFlush(seller);
            for (var line:request.items()) {
                var item=new OrderItemEntity(); item.setSellerOrderId(seller.getId()); item.setVariantIdRef(line.variantId());
                item.setProductCodeSnapshot(line.productCodeSnapshot()); item.setProductNameSnapshot(line.productNameSnapshot());
                item.setVariantNameSnapshot(line.variantNameSnapshot()); item.setSkuSnapshot(line.skuSnapshot()); item.setImageUrlSnapshot(line.imageUrlSnapshot());
                item.setQuantity(line.quantity()); item.setUnitPrice(line.unitPrice()); item.setDiscountAmount(line.discountAmount()); item.setSubtotal(line.subtotal()); items.save(item);
            }
            // Zero obligations are omitted because SQL Server payment.amount must be >0.
            if (deposit.signum()>0) obligation(seller.getId(),PaymentType.DEPOSIT,deposit,request.acceptedAt());
            if (remaining.signum()>0) obligation(seller.getId(),PaymentType.REMAINING,remaining,seller.getRemainingDueAt());
            claim.setOrderId(order.getId()); claim.setSellerOrderId(seller.getId()); conversions.save(claim);
            return response(claim,seller.getStatus());
        }); } catch (DataIntegrityViolationException concurrentClaim) {
            // The failed transaction is rolled back before reading the winner's committed result.
            return tx.execute(s -> replay(conversions.findByQuotationIdRef(request.quotationId()).orElseThrow(this::conflict),request,hash));
        }
    }
    @Override public OrderResponse getOrderByQuotation(Long quotationId) {
        var c=conversions.findByQuotationIdRef(quotationId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Quotation has not been converted"));
        return response(c,sellers.findById(c.getSellerOrderId()).orElseThrow().getStatus());
    }

    @Override
    @Transactional(readOnly = true)
    public SellerOrderPaymentContext getSellerOrderPaymentContext(Long sellerOrderId) {
        SellerOrderEntity seller = sellers.findById(sellerOrderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seller order not found"));
        return new SellerOrderPaymentContext(seller.getId(), seller.getTotalAmount(),
                seller.getDepositPercent(), seller.getRemainingDueAt(), seller.getStatus());
    }
    private OrderResponse replay(QuotationOrderConversionEntity c,CreateOrderFromQuotationRequest r,String hash) {
        if (!Objects.equals(c.getQuotationIdRef(),r.quotationId()) || !c.getIdempotencyKey().equals(r.idempotencyKey()) || !c.getRequestHash().equals(hash)) throw conflict();
        return response(c,sellers.findById(c.getSellerOrderId()).orElseThrow().getStatus());
    }
    private OrderResponse response(QuotationOrderConversionEntity c,String status) { return new OrderResponse(c.getOrderId(),status,c.getSellerOrderId(),c.getQuotationIdRef(),c.getQuotationVersion()); }
    private void obligation(Long sellerId,PaymentType type,BigDecimal amount,java.time.LocalDateTime due) {
        var p=new PaymentEntity(); p.setSellerOrderId(sellerId); p.setPaymentType(type); p.setAmount(amount); p.setDueAt(due); p.setStatus(PaymentStatus.PENDING); payments.save(p);
    }
    private void validateAmounts(CreateOrderFromQuotationRequest r) {
        BigDecimal sum=BigDecimal.ZERO;
        for (var i:r.items()) {
            BigDecimal gross=i.unitPrice().multiply(BigDecimal.valueOf(i.quantity()));
            if (i.quantity()<=0 || i.unitPrice().signum()<0 || i.discountAmount().signum()<0 || i.discountAmount().compareTo(gross)>0 || gross.subtract(i.discountAmount()).compareTo(i.subtotal())!=0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid quotation item amounts");
            sum=sum.add(i.subtotal());
        }
        if (r.items().isEmpty() || r.totalAmount().signum()<=0 || r.terms().shippingFee().signum()<0 || r.terms().depositPercent().signum()<0 || r.terms().depositPercent().compareTo(new BigDecimal("100"))>0 || sum.compareTo(r.subtotal())!=0 || sum.add(r.terms().shippingFee()).compareTo(r.totalAmount())!=0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid quotation totals");
    }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Quotation/key already has a different snapshot"); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
