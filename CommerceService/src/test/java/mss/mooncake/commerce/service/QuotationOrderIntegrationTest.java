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
    private CreateOrderFromQuotationRequest request(Long id,String key) {
        return new CreateOrderFromQuotationRequest(801L,id,1,LocalDateTime.of(2026,10,14,0,0),101L,202L,401L,key,
            new CreateOrderFromQuotationRequest.StoreSnapshot("Shop","Supplier"),
            new CreateOrderFromQuotationRequest.AddressSnapshot(301L,"Buyer","0123456789","Street",null,null,"HCM"),
            new CreateOrderFromQuotationRequest.Terms(new BigDecimal("30.00"),LocalDate.of(2026,10,24),"DELIVERY",new BigDecimal("50.00"),null),
            List.of(new CreateOrderFromQuotationRequest.Item(null,null,"Bánh custom",null,null,null,10,new BigDecimal("100.00"),BigDecimal.ZERO,new BigDecimal("1000.00"))),
            new BigDecimal("1000.00"),new BigDecimal("1050.00"));
    }
}
