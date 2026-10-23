package mss.mooncake.commerce.service;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import mss.mooncake.commerce.dto.request.CreateOrderFromQuotationRequest;
import mss.mooncake.commerce.entity.*;
import mss.mooncake.commerce.enums.PaymentType;
import mss.mooncake.commerce.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest
class QuotationOrderIntegrationTest {
    @Autowired OrderApplicationService service;
    @Autowired QuotationOrderConversionRepository conversions;
    @Autowired CustomerOrderRepository orders;
    @Autowired SellerOrderRepository sellers;
    @Autowired PaymentRepository payments;
    @Autowired OrderItemRepository items;
    @Test void snapshotsOrderAndCreatesDepositOnlyOnce() {
        var request=request(901L,"key-901");
        var first=service.createOrderFromAcceptedQuotation(request);
        var replay=service.createOrderFromAcceptedQuotation(request);
        assertThat(replay).isEqualTo(first);
        var seller=sellers.findById(first.sellerOrderId()).orElseThrow();
        assertThat(seller.getTotalAmount()).isEqualByComparingTo("1050.00");
        assertThat(seller.getDepositAmount()).isEqualByComparingTo("315.00");
        assertThat(seller.getRemainingAmount()).isEqualByComparingTo("735.00");
        assertThat(seller.getRemainingDueAt()).isEqualTo(request.terms().deliveryDate().minusDays(3).atStartOfDay());
        assertThat(payments.findAll().stream().filter(p->p.getSellerOrderId().equals(seller.getId())).toList()).hasSize(2)
            .extracting(PaymentEntity::getPaymentType).containsExactlyInAnyOrder(PaymentType.DEPOSIT,PaymentType.REMAINING);
        assertThat(service.getOrderByQuotation(901L)).isEqualTo(first);
        assertThatThrownBy(()->service.createOrderFromAcceptedQuotation(request(901L,"other-key"))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void rejectsInconsistentTotalsWithoutPartialOrder() {
        long count=orders.count();
        var good=request(902L,"key-902");
        var bad=new CreateOrderFromQuotationRequest(good.rfqId(),good.quotationId(),good.quotationVersion(),good.acceptedAt(),good.customerId(),good.supplierId(),good.storeId(),good.idempotencyKey(),good.storeSnapshot(),good.deliveryAddressSnapshot(),good.terms(),good.items(),good.subtotal(),new BigDecimal("999.00"));
        assertThatThrownBy(()->service.createOrderFromAcceptedQuotation(bad)).isInstanceOf(ResponseStatusException.class);
        assertThat(orders.count()).isEqualTo(count); assertThat(conversions.findByQuotationIdRef(902L)).isEmpty();
    }
    // Payments are whole VND (TV5 CK_payment_integer_vnd): 30% of 1005 = 301.5 rounds HALF_UP to 302.
    @Test void roundsDepositHalfUpToWholeVnd() {
        var created=service.createOrderFromAcceptedQuotation(request(903L,"key-903",1,"955.00"));
        var seller=sellers.findById(created.sellerOrderId()).orElseThrow();
        assertThat(seller.getTotalAmount()).isEqualByComparingTo("1005");
        assertThat(seller.getDepositAmount()).isEqualByComparingTo("302");
        assertThat(seller.getRemainingAmount()).isEqualByComparingTo("703");
        assertThat(payments.findAll().stream().filter(p->p.getSellerOrderId().equals(seller.getId())).map(PaymentEntity::getAmount).toList())
            .hasSize(2).allSatisfy(amount->assertThat(amount.stripTrailingZeros().scale()).isLessThanOrEqualTo(0));
    }
    @Test void rejectsFractionalDongWithoutPartialOrder() {
        long count=orders.count();
        assertThatThrownBy(()->service.createOrderFromAcceptedQuotation(request(904L,"key-904",10,"100.05"))).isInstanceOf(ResponseStatusException.class);
        assertThat(orders.count()).isEqualTo(count); assertThat(conversions.findByQuotationIdRef(904L)).isEmpty();
    }
    private CreateOrderFromQuotationRequest request(Long id,String key) { return request(id,key,10,"100.00"); }
    private CreateOrderFromQuotationRequest request(Long id,String key,int quantity,String unitPrice) {
        BigDecimal subtotal=new BigDecimal(unitPrice).multiply(BigDecimal.valueOf(quantity));
        return new CreateOrderFromQuotationRequest(801L,id,1,LocalDateTime.of(2026,10,14,0,0),101L,202L,401L,key,
            new CreateOrderFromQuotationRequest.StoreSnapshot("Shop","Supplier"),
            new CreateOrderFromQuotationRequest.AddressSnapshot(301L,"Buyer","0123456789","Street",null,null,"HCM"),
            new CreateOrderFromQuotationRequest.Terms(new BigDecimal("30.00"),LocalDate.of(2026,10,24),"DELIVERY",new BigDecimal("50.00"),null),
            List.of(new CreateOrderFromQuotationRequest.Item(null,null,"Bánh custom",null,null,null,quantity,new BigDecimal(unitPrice),BigDecimal.ZERO,subtotal)),
            subtotal,subtotal.add(new BigDecimal("50.00")));
    }
}
