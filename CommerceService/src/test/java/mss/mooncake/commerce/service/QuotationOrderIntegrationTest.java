package mss.mooncake.commerce.service;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import mss.mooncake.commerce.dto.request.CreateOrderFromQuotationRequest;
import mss.mooncake.commerce.entity.*;
import mss.mooncake.commerce.enums.PaymentType;
import mss.mooncake.commerce.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
// Conversion registers the order with TV5 (finance_context, created by Flyway V3), so this needs SQL Server:
// run it through scripts/verify-tv4-sqlserver.ps1. The H2 test profile has no Flyway schema.
@EnabledIfEnvironmentVariable(named="SPRING_DATASOURCE_URL",matches="jdbc:sqlserver:.*",disabledReason="needs SQL Server + Flyway (verify-tv4-sqlserver.ps1)")
@SpringBootTest
class QuotationOrderIntegrationTest {
    @Autowired OrderApplicationService service;
    @Autowired QuotationOrderConversionRepository conversions;
    @Autowired CustomerOrderRepository orders;
    @Autowired SellerOrderRepository sellers;
    @Autowired PaymentRepository payments;
    @Autowired OrderItemRepository items;
    @Autowired FinanceRepository finance;
    // The verification database is kept between runs; fresh quotation IDs keep each run independent.
    private final long base=Math.floorMod(System.nanoTime(),1_000_000_000L)*10;
    @Test void snapshotsOrderAndCreatesDepositOnlyOnce() {
        var request=request(base+1,"30.00");
        var first=service.createOrderFromAcceptedQuotation(request);
        var replay=service.createOrderFromAcceptedQuotation(request);
        assertThat(replay).isEqualTo(first);
        var seller=sellers.findById(first.sellerOrderId()).orElseThrow();
        assertThat(seller.getTotalAmount()).isEqualByComparingTo("1050.00");
        assertThat(seller.getDepositAmount()).isEqualByComparingTo("315.00");
        assertThat(seller.getRemainingAmount()).isEqualByComparingTo("735.00");
        assertThat(seller.getStatus()).isEqualTo("AWAITING_DEPOSIT");
        // Same due instant TV5 puts on the REMAINING obligation (00:00 Asia/Ho_Chi_Minh, stored as UTC).
        assertThat(seller.getRemainingDueAt()).isEqualTo(LocalDateTime.ofInstant(FinanceIntegrationService.remainingDue(request.terms().deliveryDate()),ZoneOffset.UTC));
        var context=finance.context(seller.getId());
        assertThat(context.total()).isEqualTo(1050L); assertThat(context.deposit()).isEqualTo(315L);
        assertThat(paymentsOf(seller)).hasSize(2).extracting(PaymentEntity::getPaymentType).containsExactlyInAnyOrder(PaymentType.DEPOSIT,PaymentType.REMAINING);
        assertThat(service.getOrderByQuotation(base+1)).isEqualTo(first);
        assertThatThrownBy(()->service.createOrderFromAcceptedQuotation(request(base+1,"other-"+base,10,"100.00","30.00"))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void rejectsInconsistentTotalsWithoutPartialOrder() {
        long count=orders.count();
        var good=request(base+2,"30.00");
        var bad=new CreateOrderFromQuotationRequest(good.rfqId(),good.quotationId(),good.quotationVersion(),good.acceptedAt(),good.customerId(),good.supplierId(),good.storeId(),good.idempotencyKey(),good.storeSnapshot(),good.deliveryAddressSnapshot(),good.terms(),good.items(),good.subtotal(),new BigDecimal("999.00"));
        assertThatThrownBy(()->service.createOrderFromAcceptedQuotation(bad)).isInstanceOf(ResponseStatusException.class);
        assertThat(orders.count()).isEqualTo(count); assertThat(conversions.findByQuotationIdRef(base+2)).isEmpty();
    }
    // Payments are whole VND (TV5 CK_payment_integer_vnd): 30% of 1005 = 301.5 rounds HALF_UP to 302.
    @Test void roundsDepositHalfUpToWholeVnd() {
        var created=service.createOrderFromAcceptedQuotation(request(base+3,"key-"+(base+3),1,"955.00","30.00"));
        var seller=sellers.findById(created.sellerOrderId()).orElseThrow();
        assertThat(seller.getTotalAmount()).isEqualByComparingTo("1005");
        assertThat(seller.getDepositAmount()).isEqualByComparingTo("302");
        assertThat(seller.getRemainingAmount()).isEqualByComparingTo("703");
        assertThat(paymentsOf(seller).stream().map(PaymentEntity::getAmount).toList())
            .hasSize(2).allSatisfy(amount->assertThat(amount.stripTrailingZeros().scale()).isLessThanOrEqualTo(0));
    }
    @Test void rejectsFractionalDongWithoutPartialOrder() {
        long count=orders.count();
        assertThatThrownBy(()->service.createOrderFromAcceptedQuotation(request(base+4,"key-"+(base+4),10,"100.05","30.00"))).isInstanceOf(ResponseStatusException.class);
        assertThat(orders.count()).isEqualTo(count); assertThat(conversions.findByQuotationIdRef(base+4)).isEmpty();
    }
    // TV5 only accepts a deposit strictly between 0 and the total; 0% and 100% become one FULL payment.
    @Test void zeroOrFullDepositBecomesSingleFullPayment() {
        for (var percent:List.of("0.00","100.00")) {
            long id=base+("0.00".equals(percent)?5:6);
            var seller=sellers.findById(service.createOrderFromAcceptedQuotation(request(id,percent)).sellerOrderId()).orElseThrow();
            assertThat(seller.getStatus()).isEqualTo("AWAITING_PAYMENT");
            assertThat(seller.getDepositAmount()).isNull(); assertThat(seller.getRemainingAmount()).isNull(); assertThat(seller.getRemainingDueAt()).isNull();
            assertThat(finance.context(seller.getId()).deposit()).isNull();
            assertThat(paymentsOf(seller)).singleElement().satisfies(p->{
                assertThat(p.getPaymentType()).isEqualTo(PaymentType.FULL); assertThat(p.getAmount()).isEqualByComparingTo("1050"); });
        }
    }
    private List<PaymentEntity> paymentsOf(SellerOrderEntity seller) {
        return payments.findAll().stream().filter(p->p.getSellerOrderId().equals(seller.getId())).toList();
    }
    private CreateOrderFromQuotationRequest request(long id,String depositPercent) { return request(id,"key-"+id,10,"100.00",depositPercent); }
    private CreateOrderFromQuotationRequest request(long id,String key,int quantity,String unitPrice,String depositPercent) {
        BigDecimal subtotal=new BigDecimal(unitPrice).multiply(BigDecimal.valueOf(quantity));
        return new CreateOrderFromQuotationRequest(801L,id,1,LocalDateTime.of(2026,10,14,0,0),101L,202L,401L,key,
            new CreateOrderFromQuotationRequest.StoreSnapshot("Shop","Supplier"),
            new CreateOrderFromQuotationRequest.AddressSnapshot(301L,"Buyer","0123456789","Street",null,null,"HCM"),
            new CreateOrderFromQuotationRequest.Terms(new BigDecimal(depositPercent),LocalDate.of(2026,10,24),"DELIVERY",new BigDecimal("50.00"),null),
            List.of(new CreateOrderFromQuotationRequest.Item(null,null,"Bánh custom",null,null,null,quantity,new BigDecimal(unitPrice),BigDecimal.ZERO,subtotal)),
            subtotal,subtotal.add(new BigDecimal("50.00")));
    }
}
