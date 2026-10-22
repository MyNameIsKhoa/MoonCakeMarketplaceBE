package mss.mooncake.commerce.service.impl;

import mss.mooncake.commerce.service.PaymentApplicationService;
import mss.mooncake.commerce.dto.internal.SellerOrderPaymentContext;
import mss.mooncake.commerce.repository.FinanceRepository;
import mss.mooncake.commerce.service.FinanceIntegrationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;

@Service
public class PaymentApplicationServiceImpl implements PaymentApplicationService {
    private final FinanceRepository repository;

    public PaymentApplicationServiceImpl(FinanceRepository repository) {
        this.repository = repository;
    }
    @Override
    public SellerOrderPaymentContext getPaymentContext(Long sellerOrderId) {
        var context = repository.context(sellerOrderId);
        var percent = context.deposit() == null ? null : BigDecimal.valueOf(context.deposit())
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(context.total()), 2, RoundingMode.HALF_UP);
        var due = FinanceIntegrationService.remainingDue(context.deliveryDate());
        return new SellerOrderPaymentContext(sellerOrderId, BigDecimal.valueOf(context.total()),
                percent, due == null ? null : LocalDateTime.ofInstant(due, ZoneOffset.UTC),
                context.accepted() ? "ACCEPTED" : "PENDING_ACCEPTANCE");
    }
}
