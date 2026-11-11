package mss.mooncake.commerce.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import mss.mooncake.commerce.dto.request.CreateOrderFromQuotationRequest;
import mss.mooncake.commerce.dto.response.OrderResponse;
import mss.mooncake.commerce.dto.internal.FinanceOrderContext;
import mss.mooncake.commerce.dto.internal.SellerOrderPaymentContext;
import mss.mooncake.commerce.entity.*;
import mss.mooncake.commerce.repository.*;
import mss.mooncake.commerce.service.FinanceIntegrationService;
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
    private final FinanceIntegrationService finance;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public OrderApplicationServiceImpl(QuotationOrderConversionRepository conversions, CustomerOrderRepository orders,
            SellerOrderRepository sellers, DeliveryAddressRepository addresses, OrderItemRepository items,
            FinanceIntegrationService finance, PlatformTransactionManager manager, ObjectMapper json) {
        this.conversions=conversions; this.orders=orders; this.sellers=sellers; this.addresses=addresses;
        this.items=items; this.finance=finance; this.tx=new TransactionTemplate(manager); this.json=json;
    }
    @Override public OrderResponse createOrderFromAcceptedQuotation(CreateOrderFromQuotationRequest request) {
        validateAmounts(request);
        String hash=hash(json.writeValueAsString(request));
        try { return tx.execute(s -> {
            Optional<QuotationOrderConversionEntity> replay=conversions.findByQuotationIdRef(request.quotationId());
            if (replay.isPresent()) return replay(replay.get(),request,hash);
            if (conversions.findByIdempotencyKey(request.idempotencyKey()).isPresent()) throw conflict();
            // Unique insert claims this quotation. Its claim and all snapshots commit atomically.
            QuotationOrderConversionEntity claim=new QuotationOrderConversionEntity(); claim.setQuotationIdRef(request.quotationId());
            claim.setQuotationVersion(request.quotationVersion()); claim.setIdempotencyKey(request.idempotencyKey()); claim.setRequestHash(hash);
            conversions.saveAndFlush(claim);
            CustomerOrderEntity order=new CustomerOrderEntity(); order.setOrderNo("RFQ-"+UUID.randomUUID()); order.setCustomerIdRef(request.customerId());
            order.setSourceType("RFQ"); order.setRfqIdRef(request.rfqId()); order.setSubtotal(request.subtotal());
            order.setDiscountTotal(BigDecimal.ZERO); order.setShippingTotal(request.terms().shippingFee());
            order.setGrandTotal(request.totalAmount()); order.setStatus("AWAITING_DEPOSIT"); order.setPlacedAt(request.acceptedAt()); orders.saveAndFlush(order);
            DeliveryAddressEntity address=new DeliveryAddressEntity(); CreateOrderFromQuotationRequest.AddressSnapshot a=request.deliveryAddressSnapshot();
            address.setOrderId(order.getId()); address.setSourceAddressIdRef(a.sourceAddressId()); address.setRecipientName(a.recipientName());
            address.setRecipientPhone(a.recipientPhone()); address.setAddressLine(a.addressLine()); address.setWard(a.ward());
            address.setDistrict(a.district()); address.setCity(a.city()); addresses.save(address);
            // null = no deposit split: one FULL payment, like retail orders (deposit/remaining stay empty).
            Long deposit=agreedDeposit(request.totalAmount(),request.terms().depositPercent());
            SellerOrderEntity seller=new SellerOrderEntity(); seller.setOrderId(order.getId()); seller.setSellerOrderNo("RFQS-"+UUID.randomUUID());
            seller.setStoreIdRef(request.storeId()); seller.setSupplierIdRef(request.supplierId()); seller.setQuotationIdRef(request.quotationId());
            seller.setStoreNameSnapshot(request.storeSnapshot().storeName()); seller.setSupplierNameSnapshot(request.storeSnapshot().supplierName());
            seller.setSubtotal(request.subtotal()); seller.setShippingFee(request.terms().shippingFee()); seller.setTotalAmount(request.totalAmount());
            seller.setDepositPercent(request.terms().depositPercent());
            if (deposit!=null) {
                // Same due instant TV5 puts on the REMAINING obligation (00:00 Asia/Ho_Chi_Minh), stored as UTC.
                seller.setDepositAmount(BigDecimal.valueOf(deposit)); seller.setRemainingAmount(request.totalAmount().subtract(BigDecimal.valueOf(deposit)));
                seller.setRemainingDueAt(LocalDateTime.ofInstant(FinanceIntegrationService.remainingDue(request.terms().deliveryDate()),ZoneOffset.UTC));
            }
            seller.setDeliveryDateSnapshot(request.terms().deliveryDate());
            seller.setShippingMethodSnapshot(request.terms().shippingMethod()); seller.setPaymentTermsSnapshot(request.terms().paymentTerms());
            seller.setStatus(deposit!=null?"AWAITING_DEPOSIT":"AWAITING_PAYMENT"); order.setStatus(seller.getStatus()); sellers.saveAndFlush(seller);
            for (CreateOrderFromQuotationRequest.Item line:request.items()) {
                OrderItemEntity item=new OrderItemEntity(); item.setSellerOrderId(seller.getId()); item.setVariantIdRef(line.variantId());
                item.setProductCodeSnapshot(line.productCodeSnapshot()); item.setProductNameSnapshot(line.productNameSnapshot());
                item.setVariantNameSnapshot(line.variantNameSnapshot()); item.setSkuSnapshot(line.skuSnapshot()); item.setImageUrlSnapshot(line.imageUrlSnapshot());
                item.setQuantity(line.quantity()); item.setUnitPrice(line.unitPrice()); item.setDiscountAmount(line.discountAmount()); item.setSubtotal(line.subtotal()); items.save(item);
            }
            // TV5 owns payment obligations: register the accepted order like retail checkout, in this transaction,
            // so finance_context and DEPOSIT/REMAINING (or FULL) commit or roll back with the order.
            finance.syncOrder(new FinanceOrderContext(seller.getId(),request.customerId(),request.supplierId(),
                request.totalAmount().longValueExact(),deposit,request.terms().deliveryDate(),true,1));
            claim.setOrderId(order.getId()); claim.setSellerOrderId(seller.getId()); conversions.save(claim);
            return response(claim,seller.getStatus());
        }); } catch (DataIntegrityViolationException concurrentClaim) {
            // The failed transaction is rolled back before reading the winner's committed result.
            return tx.execute(s -> replay(conversions.findByQuotationIdRef(request.quotationId()).orElseThrow(this::conflict),request,hash));
        }
    }
    @Override public OrderResponse getOrderByQuotation(Long quotationId) {
        QuotationOrderConversionEntity c=conversions.findByQuotationIdRef(quotationId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Quotation has not been converted"));
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
    /** Deposit HALF_UP to the whole dong; TV5 takes it only strictly between 0 and the total, else null (FULL). */
    static Long agreedDeposit(BigDecimal total,BigDecimal percent) {
        BigDecimal deposit=total.multiply(percent).divide(new BigDecimal("100"),0,RoundingMode.HALF_UP);
        return deposit.signum()>0 && deposit.compareTo(total)<0 ? deposit.longValueExact() : null;
    }
    private void validateAmounts(CreateOrderFromQuotationRequest r) {
        // Payment rows must be whole VND (CK_payment_integer_vnd); reject before anything is persisted.
        if (!wholeVnd(r.terms().shippingFee()) || r.items().stream().anyMatch(i->!wholeVnd(i.unitPrice()) || !wholeVnd(i.discountAmount()))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Quotation amounts must be whole VND");
        BigDecimal sum=BigDecimal.ZERO;
        for (CreateOrderFromQuotationRequest.Item i:r.items()) {
            BigDecimal gross=i.unitPrice().multiply(BigDecimal.valueOf(i.quantity()));
            if (i.quantity()<=0 || i.unitPrice().signum()<0 || i.discountAmount().signum()<0 || i.discountAmount().compareTo(gross)>0 || gross.subtract(i.discountAmount()).compareTo(i.subtotal())!=0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid quotation item amounts");
            sum=sum.add(i.subtotal());
        }
        if (r.items().isEmpty() || r.totalAmount().signum()<=0 || r.terms().shippingFee().signum()<0 || r.terms().depositPercent().signum()<0 || r.terms().depositPercent().compareTo(new BigDecimal("100"))>0 || sum.compareTo(r.subtotal())!=0 || sum.add(r.terms().shippingFee()).compareTo(r.totalAmount())!=0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid quotation totals");
    }
    private static boolean wholeVnd(BigDecimal amount) { return amount.remainder(BigDecimal.ONE).signum()==0; }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Quotation/key already has a different snapshot"); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
